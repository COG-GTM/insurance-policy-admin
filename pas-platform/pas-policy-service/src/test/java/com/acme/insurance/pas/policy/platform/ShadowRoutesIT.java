package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pas.routing.new-business=shadow", "pas.routing.underwriting=shadow"})
@ActiveProfiles("it")
class ShadowRoutesIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void shadowRoutesWriteOnlyShadowResults() throws Exception {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String createJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "shadow-agent",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    String body =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000,
         "deductible":500,"totalPremium":1200}
        """
            .formatted(custId, LocalDate.now());
    ResponseEntity<String> created =
        post("/api/v1/policies", body, createJwt, "shadow-" + UUID.randomUUID());
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    assertThat(MAPPER.readTree(created.getBody()).path("shadow").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas_shadow.new_business_results", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.policies", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.coverages", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.idempotency_keys", Integer.class))
        .isZero();

    Long shadowResultMarker =
        jdbc.queryForObject("SELECT max(id) FROM pas_shadow.new_business_results", Long.class);
    String otherCustId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", otherCustId);
    String invalidPolicyTypeBody =
        body.replace("\"policyType\":\"AUT\"", "\"policyType\":\"AUTOMOBILE\"");
    ResponseEntity<String> invalidPolicyType =
        post("/api/v1/policies", invalidPolicyTypeBody, createJwt, null);
    ResponseEntity<String> invalidPolicyTypeWithOtherHolder =
        post(
            "/api/v1/policies",
            invalidPolicyTypeBody.replace(custId, otherCustId),
            createJwt,
            null);
    assertThat(invalidPolicyType.getStatusCode().value()).isEqualTo(202);
    assertThat(invalidPolicyTypeWithOtherHolder.getStatusCode().value()).isEqualTo(202);
    List<String> invalidHashes =
        jdbc.queryForList(
            "SELECT request_hash FROM pas_shadow.new_business_results WHERE id > ? ORDER BY id",
            String.class,
            shadowResultMarker);
    assertThat(invalidHashes).hasSize(2);
    assertThat(invalidHashes.get(0)).isEqualTo(invalidHashes.get(1));
    List<String> invalidPolicyTypes =
        jdbc.queryForList(
            "SELECT policy_type FROM pas_shadow.new_business_results WHERE id > ? ORDER BY id",
            String.class,
            shadowResultMarker);
    assertThat(invalidPolicyTypes).hasSize(2);
    assertThat(invalidPolicyTypes.get(0)).isNull();
    assertThat(invalidPolicyTypes.get(1)).isNull();

    String policyNumber = "POL000000021";
    jdbc.update(
        """
        INSERT INTO pas.policies (
          policy_number, policy_type, policy_status, effective_date, expiry_date,
          policyholder_id, agent_code, branch_code, total_premium, deductible,
          coverage_limit, inception_date, uw_status, row_version
        ) VALUES (?, 'AUT', 'PN', ?, ?, ?, 'AGT001', 'B001', 1200, 500, 100000, ?, 'PN', 7)
        """,
        policyNumber,
        LocalDate.now(),
        LocalDate.now().plusYears(1),
        custId,
        LocalDate.now());
    String decideJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.UNDERWRITING_DECIDE,
            "shadow-underwriter",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    ResponseEntity<String> decision =
        post("/api/v1/policies/" + policyNumber + "/underwriting-decisions", "", decideJwt, null);
    JsonNode decisionBody = MAPPER.readTree(decision.getBody());
    assertThat(decision.getStatusCode().value()).isEqualTo(200);
    assertThat(decisionBody.path("shadow").asBoolean()).isTrue();
    assertThat(decisionBody.path("decisionId").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas_shadow.underwriting_results", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM pas.underwriting_decisions", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT row_version FROM pas.policies WHERE policy_number = ?",
                Long.class,
                policyNumber))
        .isEqualTo(7L);
    ResponseEntity<String> listed =
        restTemplate.exchange(
            url("/api/v1/policies/" + policyNumber + "/underwriting-decisions"),
            HttpMethod.GET,
            new HttpEntity<>(
                headers(
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ,
                        "shadow-reader",
                        Map.of(
                            "book",
                            "AGENT",
                            "agent_id",
                            "AGT001",
                            "branch_codes",
                            List.of("B001"))))),
            String.class);
    assertThat(listed.getStatusCode().value()).isEqualTo(200);
    assertThat(MAPPER.readTree(listed.getBody())).isEmpty();
    ResponseEntity<String> override =
        post(
            "/api/v1/policies/" + policyNumber + "/underwriting-decisions/1/override",
            "{\"decisionCode\":\"AP\",\"reason\":\"test\"}",
            PolicyJwtTestSupport.mint(PasScopes.UNDERWRITING_OVERRIDE, "shadow-override", Map.of()),
            null);
    assertThat(override.getStatusCode().value()).isEqualTo(503);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class)).isZero();
  }

  private ResponseEntity<String> post(String path, String body, String jwt, String key) {
    HttpHeaders headers = headers(jwt);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    return restTemplate.exchange(
        url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private HttpHeaders headers(String jwt) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    return headers;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
