package com.acme.insurance.pas.policy.underwriting;

import static com.acme.insurance.pas.policy.WriteApiSupport.send;
import static com.acme.insurance.pas.policy.WriteApiSupport.today;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.WriteApiSupport;
import com.acme.insurance.pas.policy.claims.InMemoryClaimsHistoryAdapter;
import com.acme.insurance.pas.policy.claims.InMemoryClaimsHistoryAdapter.Claim;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AB-525: underwriting decisions and overrides (UNDWRT) end to end against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UnderwritingApiIT {

  static final String DECIDE = PasScopes.UNDERWRITING_DECIDE;
  static final String OVERRIDE_BODY =
      "{\"decisionCode\":\"AP\",\"reason\":\"Claims were subrogated; reviewed by UW\"}";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
    registry.add("pas.outbox.relay.enabled", () -> "false");
    registry.add("pas.claims.adapter", () -> "stub");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private InMemoryClaimsHistoryAdapter claims;
  @Autowired private ObjectMapper mapper;

  private static boolean seeded;

  @BeforeEach
  void seed() {
    claims.clear();
    if (seeded) {
      return;
    }
    WriteApiSupport.holder(holders, "UWHOLD0001", 700, "S");
    WriteApiSupport.holder(holders, "UWHOLD0002", null, "S");
    // 700 credit, standard tier -> base score 100
    WriteApiSupport.policy(
        jdbc, "POLUW0000001", "UWHOLD0001", "AG0001", "BR01", "PN", "1000.00", "100000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000002", "UWHOLD0001", "AG0001", "BR01", "PN", "1000.00", "100000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000003", "UWHOLD0001", "AG0001", "BR01", "PN", "1000.00", "100000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000004", "UWHOLD0002", "AG0001", "BR01", "PN", "1000.00", "100000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000005", "UWHOLD0001", "AG0001", "BR02", "PN", "1000.00", "100000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000006", "UWHOLD0001", "AG0009", "BR02", "AC", "1000.00", "600000000.00");
    WriteApiSupport.policy(
        jdbc, "POLUW0000007", "UWHOLD0001", "AG0001", "BR01", "PN", "1000.00", "100000.00");
    seeded = true;
  }

  private static String underwriter() {
    return TestJwtIssuer.bearer()
        .subject("uw-jane")
        .scopes(DECIDE, PasScopes.POLICY_READ)
        .agent("AG0001")
        .sign();
  }

  private ResponseEntity<String> decide(String number, String bearer) {
    return send(
        rest,
        HttpMethod.POST,
        "/api/v1/policies/" + number + "/underwriting-decisions",
        bearer,
        null,
        Map.of());
  }

  private ResponseEntity<String> override(String number, String bearer, String body) {
    return send(
        rest,
        HttpMethod.POST,
        "/api/v1/policies/" + number + "/underwriting-decisions/overrides",
        bearer,
        body,
        Map.of());
  }

  @Test
  void automatedAcceptUpdatesPolicyAndWritesAuditAndEvent() throws Exception {
    ResponseEntity<String> res = decide("POLUW0000001", underwriter());
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode d = mapper.readTree(res.getBody());
    assertThat(d.get("decisionCode").asText()).isEqualTo("AP");
    assertThat(d.get("riskScore").asInt()).isEqualTo(100);
    assertThat(d.get("decisionReason").asText()).isEqualTo("AUTO-ACCEPTED: LOW RISK");
    assertThat(d.get("underwriterId").asText()).isEqualTo("SYSTEM");
    assertThat(d.get("decisionDate").asText()).isEqualTo(today().toString());
    assertThat(d.get("override").asBoolean()).isFalse();

    Map<String, Object> policy =
        jdbc.queryForMap(
            "SELECT uw_status, risk_score, updated_by, version FROM pas.policies"
                + " WHERE policy_number = 'POLUW0000001'");
    assertThat(policy.get("uw_status")).isEqualTo("AP");
    assertThat(((Number) policy.get("risk_score")).intValue()).isEqualTo(100);
    assertThat(((String) policy.get("updated_by")).strip()).isEqualTo("UNDWRT");
    assertThat(((Number) policy.get("version")).longValue()).isEqualTo(1);

    JsonNode event = lastEvent("POLUW0000001");
    assertThat(event.get("eventType").asText()).isEqualTo("UnderwritingDecided");
    assertThat(event.get("policyVersion").asLong()).isEqualTo(1);
    assertThat(event.at("/data/decisionId").asLong()).isEqualTo(d.get("decisionId").asLong());
  }

  @Test
  void claimsHistoryFromThePortDrivesAReferral() throws Exception {
    for (int i = 0; i < 6; i++) {
      claims.add(
          new Claim("POLUW0000002", today().minusYears(1), "CLOSED", new BigDecimal("10.00")));
    }
    JsonNode d = mapper.readTree(decide("POLUW0000002", underwriter()).getBody());
    assertThat(d.get("riskScore").asInt()).isEqualTo(100 + 150 + 200);
    assertThat(d.get("decisionCode").asText()).isEqualTo("RS");
    JsonNode event = lastEvent("POLUW0000002");
    assertThat(event.get("eventType").asText()).isEqualTo("UnderwritingReferred");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM pas.outbox WHERE message_key = 'POLUW0000002'", String.class))
        .isEqualTo("pas.underwriting.events.v1");
  }

  @Test
  void branchAccumulationOfActivePoliciesAddsThreeHundred() throws Exception {
    JsonNode d = mapper.readTree(decide("POLUW0000005", underwriter()).getBody());
    assertThat(d.get("riskScore").asInt()).isEqualTo(400);
    assertThat(d.get("decisionCode").asText()).isEqualTo("RS");
    assertThat(d.get("decisionReason").asText()).isEqualTo("REFERRED TO SENIOR UNDERWRITER");
  }

  @Test
  void sameDayDecisionsAndOverridesAreAllKept() throws Exception {
    assertThat(override("POLUW0000003", underwriter(), OVERRIDE_BODY).getStatusCode().value())
        .isEqualTo(409);
    decide("POLUW0000003", underwriter());
    claims.add(new Claim("POLUW0000003", today(), "OPEN", new BigDecimal("900.00")));
    JsonNode second = mapper.readTree(decide("POLUW0000003", underwriter()).getBody());
    assertThat(second.get("decisionCode").asText()).isEqualTo("RS");

    ResponseEntity<String> res = override("POLUW0000003", underwriter(), OVERRIDE_BODY);
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode o = mapper.readTree(res.getBody());
    assertThat(o.get("override").asBoolean()).isTrue();
    assertThat(o.get("overrideBy").asText()).isEqualTo("uw-jane");
    assertThat(o.get("overrideReason").asText())
        .isEqualTo("Claims were subrogated; reviewed by UW");
    assertThat(o.get("riskScore").asInt()).isEqualTo(second.get("riskScore").asInt());
    assertThat(
            jdbc.queryForObject(
                "SELECT uw_status FROM pas.policies WHERE policy_number = 'POLUW0000003'",
                String.class))
        .isEqualTo("AP");
    assertThat(lastEvent("POLUW0000003").at("/data/override").asBoolean()).isTrue();
    assertThat(lastEvent("POLUW0000003").toString()).doesNotContain("subrogated");

    JsonNode history =
        mapper.readTree(
            send(
                    rest,
                    HttpMethod.GET,
                    "/api/v1/policies/POLUW0000003/underwriting-decisions",
                    underwriter(),
                    null,
                    Map.of())
                .getBody());
    assertThat(history).hasSize(3);
    assertThat(history.get(0).get("decisionDate").asText())
        .isEqualTo(history.get(1).get("decisionDate").asText());
  }

  @Test
  void invalidOverrideIs400() {
    decide("POLUW0000007", underwriter());
    assertThat(
            override("POLUW0000007", underwriter(), "{\"decisionCode\":\"XX\",\"reason\":\"r\"}")
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            override("POLUW0000007", underwriter(), "{\"decisionCode\":\"AP\",\"reason\":\" \"}")
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  @Test
  void missingCreditScoreIsTheCobolNotFoundMessage() throws Exception {
    ResponseEntity<String> res = decide("POLUW0000004", underwriter());
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(res.getBody()).get("message").asText())
        .isEqualTo("POLICY/CUSTOMER DATA NOT FOUND");
  }

  @ParameterizedTest
  @ValueSource(strings = {"decide", "override"})
  void onlyAuthorisedEntitledCallersMayDecide(String endpoint) {
    String readOnly = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).book("*").sign();
    String otherAgent = TestJwtIssuer.bearer().scopes(DECIDE).agent("AG0002").sign();
    assertThat(call(endpoint, "POLUW0000001", null)).isEqualTo(401);
    assertThat(call(endpoint, "POLUW0000001", readOnly)).isEqualTo(403);
    assertThat(call(endpoint, "POLUW0000001", otherAgent)).isEqualTo(404);
    assertThat(call(endpoint, "POLXX0000000", underwriter())).isEqualTo(404);
  }

  private int call(String endpoint, String number, String bearer) {
    ResponseEntity<String> res =
        endpoint.equals("decide")
            ? decide(number, bearer)
            : override(number, bearer, OVERRIDE_BODY);
    return res.getStatusCode().value();
  }

  private JsonNode lastEvent(String number) throws Exception {
    return mapper.readTree(
        jdbc.queryForObject(
            "SELECT payload FROM pas.outbox WHERE message_key = ? ORDER BY outbox_id DESC LIMIT 1",
            String.class,
            number));
  }
}
