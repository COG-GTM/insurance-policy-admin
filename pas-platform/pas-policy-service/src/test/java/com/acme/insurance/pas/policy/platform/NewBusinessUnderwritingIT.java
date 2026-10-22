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
import org.springframework.http.HttpStatus;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class NewBusinessUnderwritingIT {
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
  void createsIdempotentPolicyAndStoresMultipleSameDayUnderwritingDecisions() throws Exception {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String agentJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "policy-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    String request =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000.00,
         "deductible":500.00,"totalPremium":1200.00}
        """
            .formatted(custId, LocalDate.now().plusDays(1));

    ResponseEntity<String> created = post("/api/v1/policies", request, agentJwt, "create-once");
    ResponseEntity<String> replay = post("/api/v1/policies", request, agentJwt, "create-once");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
    assertThat(replay.getBody()).isEqualTo(created.getBody());
    JsonNode policyResponse = MAPPER.readTree(created.getBody());
    String policyNumber = policyResponse.path("policyNumber").asText();
    assertThat(
            jdbc.queryForObject(
                "SELECT row_version FROM pas.policies WHERE policy_number = ?",
                Long.class,
                policyNumber))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.coverages WHERE policy_number = ?",
                Integer.class,
                policyNumber))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.outbox WHERE aggregate_id = ?",
                Integer.class,
                policyNumber))
        .isEqualTo(2);

    String decideJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.UNDERWRITING_DECIDE,
            "underwriter",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    ResponseEntity<String> firstDecision =
        post("/api/v1/policies/" + policyNumber + "/underwriting-decisions", "", decideJwt, null);
    ResponseEntity<String> secondDecision =
        post("/api/v1/policies/" + policyNumber + "/underwriting-decisions", "", decideJwt, null);
    assertThat(firstDecision.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(secondDecision.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode first = MAPPER.readTree(firstDecision.getBody());
    JsonNode second = MAPPER.readTree(secondDecision.getBody());
    assertThat(first.path("decisionId").asLong()).isNotEqualTo(second.path("decisionId").asLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.underwriting_decisions WHERE policy_number = ?",
                Integer.class,
                policyNumber))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT row_version FROM pas.policies WHERE policy_number = ?",
                Long.class,
                policyNumber))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "SELECT decided_by FROM pas.underwriting_decisions WHERE decision_id = ?",
                String.class,
                second.path("decisionId").asLong()))
        .isEqualTo("underwriter");

    String overrideJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.UNDERWRITING_OVERRIDE,
            "senior-underwriter",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    ResponseEntity<String> override =
        post(
            "/api/v1/policies/"
                + policyNumber
                + "/underwriting-decisions/"
                + second.path("decisionId").asLong()
                + "/override",
            "{\"decisionCode\":\"AP\",\"reason\":\"Reviewed and approved\"}",
            overrideJwt,
            null);
    assertThat(override.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(MAPPER.readTree(override.getBody()).path("overridesDecisionId").asLong())
        .isEqualTo(second.path("decisionId").asLong());
  }

  @Test
  void validatesAllPolnewFieldsAndEnforcesAgentBranchEntitlement() throws Exception {
    String badRequest =
        """
        {"policyType":"","policyholderId":"","effectiveDate":"%s","coverageLimit":0}
        """
            .formatted(LocalDate.now().minusDays(1));
    String writeJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "policy-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    ResponseEntity<String> invalid = post("/api/v1/policies", badRequest, writeJwt, null);
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(invalid.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(invalid.getBody())
        .contains("POLICYHOLDER ID IS REQUIRED")
        .contains("POLICY TYPE IS REQUIRED")
        .contains("EFFECTIVE DATE CANNOT BE IN PAST")
        .contains("POLICY LIMIT MUST BE GREATER THAN ZERO");

    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String unentitledRequest =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"OTHER1","branchCode":"X001","coverageLimit":100000}
        """
            .formatted(custId, LocalDate.now().plusDays(1));
    ResponseEntity<String> forbidden = post("/api/v1/policies", unentitledRequest, writeJwt, null);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.policies WHERE policyholder_id = ?",
                Integer.class,
                custId))
        .isZero();
  }

  private ResponseEntity<String> post(String path, String body, String jwt, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }
}
