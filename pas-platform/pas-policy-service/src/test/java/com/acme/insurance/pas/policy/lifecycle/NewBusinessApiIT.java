package com.acme.insurance.pas.policy.lifecycle;

import static com.acme.insurance.pas.policy.WriteApiSupport.createBody;
import static com.acme.insurance.pas.policy.WriteApiSupport.send;
import static com.acme.insurance.pas.policy.WriteApiSupport.today;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.WriteApiSupport;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** AB-525: POST /api/v1/policies (POLNEW) end to end against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NewBusinessApiIT {

  static final String PATH = "/api/v1/policies";
  static final String WRITE = PasScopes.POLICY_WRITE;

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
  @Autowired private ObjectMapper mapper;

  private static boolean seeded;

  @BeforeEach
  void seed() {
    if (!seeded) {
      WriteApiSupport.holder(holders, "CUSTNB0001", 700, "S");
      seeded = true;
    }
  }

  private static String writer(String agent) {
    return TestJwtIssuer.bearer().scopes(WRITE, PasScopes.POLICY_READ).agent(agent).sign();
  }

  private ResponseEntity<String> create(String body, String bearer, Map<String, String> headers) {
    return send(rest, HttpMethod.POST, PATH, bearer, body, headers);
  }

  @Test
  void createsPendingPolicyWithDefaultCoveragesAndOutboxEvent() throws Exception {
    ResponseEntity<String> res =
        create(
            createBody("CUSTNB0001", "AUT", today(), "100000.00", "AG0001"),
            writer("AG0001"),
            Map.of("X-Correlation-Id", "nb-corr-1"));
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode body = mapper.readTree(res.getBody());
    String number = body.at("/policy/policyNumber").asText();
    assertThat(number).matches("POL\\d{9}");
    assertThat(res.getHeaders().getLocation()).hasToString(PATH + "/" + number);
    assertThat(res.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
    assertThat(body.at("/policy/policyStatus").asText()).isEqualTo("PN");
    assertThat(body.at("/policy/uwStatus").asText()).isEqualTo("PN");
    assertThat(body.at("/policy/apiFlag").asText()).isEqualTo("N");
    assertThat(body.at("/policy/updatedBy").asText()).isEqualTo("POLNEW");
    assertThat(body.at("/policy/inceptionDate").asText()).isEqualTo(today().toString());
    assertThat(body.at("/coverages/0/coverageType").asText()).isEqualTo("AUTL");
    assertThat(body.at("/coverages/1/coverageType").asText()).isEqualTo("AUTP");
    assertThat(body.at("/coverages/1/sequenceNum").asInt()).isEqualTo(2);
    assertThat(body.at("/coverages/0/coverageLimit").decimalValue()).isEqualByComparingTo("100000");

    ResponseEntity<String> read =
        send(rest, HttpMethod.GET, PATH + "/" + number, writer("AG0001"), null, Map.of());
    assertThat(read.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(read.getBody()).get("policyNumber").asText()).isEqualTo(number);

    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT topic, message_key, event_type, payload FROM pas.outbox WHERE message_key = ?",
            number);
    assertThat(event)
        .containsEntry("topic", "pas.policy.events.v1")
        .containsEntry("event_type", "PolicyCreated");
    JsonNode payload = mapper.readTree((String) event.get("payload"));
    assertThat(payload.get("correlationId").asText()).isEqualTo("nb-corr-1");
    assertThat(payload.get("policyVersion").asLong()).isZero();
    assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(payload.at("/data/coverages")).hasSize(2);
    assertThat((String) event.get("payload")).doesNotContain("Tester");
  }

  @Test
  void polnewValidationReturnsTheCobolMessage() throws Exception {
    assertRejected(
        createBody("NOSUCHCUST", "AUT", today(), "1000", "AG0001"),
        "POLNEW_VALIDATION",
        "POLICYHOLDER NOT FOUND IN SYSTEM");
    assertRejected(
        createBody("CUSTNB0001", "AUT", today().minusDays(1), "1000", "AG0001"),
        "POLNEW_VALIDATION",
        "EFFECTIVE DATE CANNOT BE IN PAST");
    assertRejected(
        createBody("CUSTNB0001", "AUT", today(), "0", "AG0001"),
        "POLNEW_VALIDATION",
        "POLICY LIMIT MUST BE GREATER THAN ZERO");
    assertRejected(
        createBody("CUSTNB0001", "", today().minusDays(3), "0", "AG0001"),
        "POLNEW_VALIDATION",
        "POLICY LIMIT MUST BE GREATER THAN ZERO");
    String badTerm =
        createBody("CUSTNB0001", "AUT", today(), "1000", "AG0001")
            .replace("\"expiryDate\":\"" + today().plusYears(1), "\"expiryDate\":\"" + today());
    assertRejected(badTerm, "INVALID_TERM", "EXPIRY DATE MUST BE AFTER EFFECTIVE DATE");
  }

  private void assertRejected(String body, String code, String message) throws Exception {
    ResponseEntity<String> res = create(body, writer("AG0001"), Map.of());
    assertThat(res.getStatusCode().value()).as(message).isEqualTo(422);
    JsonNode error = mapper.readTree(res.getBody());
    assertThat(error.get("code").asText()).isEqualTo(code);
    assertThat(error.get("message").asText()).isEqualTo(message);
    assertThat(error.get("path").asText()).isEqualTo(PATH);
  }

  @Test
  void malformedRequestsAre400() {
    String tooLong = createBody("CUSTNB00011", "AUT", today(), "1000", "AG0001");
    assertThat(create(tooLong, writer("AG0001"), Map.of()).getStatusCode().value()).isEqualTo(400);
    String noDate =
        createBody("CUSTNB0001", "AUT", today(), "1000", "AG0001")
            .replace("\"effectiveDate\":\"" + today() + "\",", "");
    assertThat(create(noDate, writer("AG0001"), Map.of()).getStatusCode().value()).isEqualTo(400);
    String body = createBody("CUSTNB0001", "AUT", today(), "1000", "AG0001");
    assertThat(
            create(body, writer("AG0001"), Map.of("Idempotency-Key", " ")).getStatusCode().value())
        .isEqualTo(400);
  }

  @Test
  void onlyAuthorisedCallersMayCreate() throws Exception {
    String body = createBody("CUSTNB0001", "AUT", today(), "1000", "AG0001");
    assertThat(create(body, null, Map.of()).getStatusCode().value()).isEqualTo(401);
    String readOnly = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).book("*").sign();
    assertThat(create(body, readOnly, Map.of()).getStatusCode().value()).isEqualTo(403);
    ResponseEntity<String> crossTenant = create(body, writer("AG0002"), Map.of());
    assertThat(crossTenant.getStatusCode().value()).isEqualTo(403);
    assertThat(mapper.readTree(crossTenant.getBody()).get("code").asText())
        .isEqualTo("NOT_ENTITLED");
    String branchWriter = TestJwtIssuer.bearer().scopes(WRITE).branch("B001").sign();
    assertThat(create(body, branchWriter, Map.of()).getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void idempotencyKeyReplaysTheFirstResult() throws Exception {
    String body = createBody("CUSTNB0001", "HOM", today().plusDays(5), "250000", "AG0001");
    Map<String, String> key = Map.of("Idempotency-Key", "idem-nb-1");
    ResponseEntity<String> first = create(body, writer("AG0001"), key);
    ResponseEntity<String> second = create(body, writer("AG0001"), key);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(second.getStatusCode().value()).isEqualTo(201);
    assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    String number = mapper.readTree(first.getBody()).at("/policy/policyNumber").asText();
    assertThat(mapper.readTree(second.getBody()).at("/policy/policyNumber").asText())
        .isEqualTo(number);
    assertThat(mapper.readTree(second.getBody()).get("coverages")).hasSize(2);
    assertThat(mapper.readTree(second.getBody()).at("/policy/uwStatus").asText()).isEqualTo("PN");

    String underwriter =
        TestJwtIssuer.bearer().scopes(PasScopes.UNDERWRITING_DECIDE).agent("AG0001").sign();
    ResponseEntity<String> decided =
        send(
            rest,
            HttpMethod.POST,
            PATH + "/" + number + "/underwriting-decisions",
            underwriter,
            null,
            Map.of());
    assertThat(decided.getStatusCode().value()).isEqualTo(201);
    ResponseEntity<String> afterUnderwriting = create(body, writer("AG0001"), key);
    assertThat(afterUnderwriting.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(mapper.readTree(afterUnderwriting.getBody()))
        .isEqualTo(mapper.readTree(first.getBody()));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pas.outbox WHERE message_key = ?"
                    + " AND event_type = 'PolicyCreated'",
                Integer.class,
                number))
        .isEqualTo(1);

    String changed = body.replace("250000", "260000");
    ResponseEntity<String> reused = create(changed, writer("AG0001"), key);
    assertThat(reused.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(reused.getBody()).get("code").asText())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");

    String otherSubject =
        TestJwtIssuer.bearer().subject("someone-else").scopes(WRITE).agent("AG0001").sign();
    ResponseEntity<String> other = create(body, otherSubject, key);
    assertThat(mapper.readTree(other.getBody()).at("/policy/policyNumber").asText())
        .isNotEqualTo(number);
  }
}
