package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.policy.dev.FacadeSeedLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
class PolicyInquirySecurityIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private FacadeSeedLoader seedLoader;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @BeforeEach
  void seedFacadeData() {
    seedLoader.load();
  }

  @Test
  void requiresPolicyScopeAndConcealsUnknownOrUnentitledObjects() throws Exception {
    for (String path : inquiryPaths()) {
      assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(get(path, PolicyJwtTestSupport.mint("other")).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
    assertNotFound("/api/v1/policies/POL-00000001", Map.of("agent_id", "AG9999"));
    assertNotFound("/api/v1/policies/POL-00000001", Map.of("branch_codes", List.of("NYC1")));
    assertNotFound("/api/v1/policies/POL-00000001", Map.of());
    assertNotFound("/api/v1/policies/POL-00000001/coverages", Map.of("agent_id", "AG9999"));
    assertNotFound("/api/v1/policies/POL-00000001/policyholder", Map.of("agent_id", "AG9999"));

    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG1001")))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000002",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "AG1001")))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("agent_id", "ag1001")))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ, Map.of("branch_codes", List.of("CHI1"))))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000002",
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ, Map.of("branch_codes", List.of("CHI1"))))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000003",
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ, Map.of("branch_codes", List.of("CHI1"))))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ, Map.of("branch_codes", List.of("chi1"))))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    String longSubject = "a".repeat(256);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(
                        PasScopes.POLICY_READ, longSubject, Map.of("book", "ALL")))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            get(
                    "/api/v1/policies/POL-00000001",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, null, Map.of("book", "ALL")))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void doesNotExposePoliciesWithBlankOrNullAgentCodesWithoutMatchingBranchEntitlement()
      throws Exception {
    insertPolicyWithAgentCode("POL-90000001", "      ");
    insertPolicyWithAgentCode("POL-90000002", null);

    for (String policyNumber : List.of("POL-90000001", "POL-90000002")) {
      for (String path :
          List.of(
              "/api/v1/policies/" + policyNumber,
              "/api/v1/policies/" + policyNumber + "/coverages",
              "/api/v1/policies/" + policyNumber + "/policyholder")) {
        assertNotFound(path, Map.of("branch_codes", List.of("NYC1")));
        assertNotFound(path, Map.of());
      }
    }
  }

  @Test
  void masksPolicyholderPiiAndOmitsCreditScoreUnlessScopeIsGranted() throws Exception {
    String path = "/api/v1/policies/POL-00000001/policyholder";
    ResponseEntity<String> masked =
        get(path, PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("book", "ALL")));
    JsonNode maskedBody = objectMapper.readTree(masked.getBody());

    assertThat(masked.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(masked.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(maskedBody.path("dateOfBirth").isNull()).isTrue();
    assertThat(maskedBody.path("birthYear").asInt()).isEqualTo(1975);
    assertThat(maskedBody.path("ssnLast4").isNull()).isTrue();
    assertThat(maskedBody.has("taxId")).isTrue();
    assertThat(maskedBody.path("taxId").isNull()).isTrue();
    assertThat(maskedBody.has("creditScore")).isFalse();
    assertThat(maskedBody.path("piiMasked").asBoolean()).isTrue();

    ResponseEntity<String> unmasked =
        get(
            path,
            PolicyJwtTestSupport.mint(
                PasScopes.POLICY_READ + " " + PasScopes.PII_READ, Map.of("book", "ALL")));
    JsonNode unmaskedBody = objectMapper.readTree(unmasked.getBody());
    assertThat(unmaskedBody.path("dateOfBirth").asText()).isEqualTo("1975-06-15");
    assertThat(unmaskedBody.path("creditScore").asInt()).isEqualTo(720);
    assertThat(unmaskedBody.path("ssnLast4").isNull()).isTrue();
    assertThat(unmaskedBody.path("taxId").isNull()).isTrue();
    assertThat(unmaskedBody.path("piiMasked").asBoolean()).isFalse();

    JsonNode corporate =
        objectMapper.readTree(
            get(
                    "/api/v1/policies/POL-00000003/policyholder",
                    PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, Map.of("book", "ALL")))
                .getBody());
    assertThat(corporate.path("taxId").asText()).isEqualTo("******4567");
    assertThat(corporate.path("riskTier").asText()).isEqualTo("S");
  }

  @Test
  void returnsEmptyNotFoundWhenPolicyholderIsMissing() throws Exception {
    jdbcTemplate.update("DELETE FROM pas.policy_holders WHERE cust_id = ?", "C000000002");
    assertNotFound("/api/v1/policies/POL-00000002/policyholder", Map.of("book", "ALL"));
    assertNotFound("/api/v1/policies/POL-99999999/policyholder", Map.of("book", "ALL"));
  }

  private void assertNotFound(String path, Map<String, Object> claims) throws Exception {
    ResponseEntity<String> response =
        get(path, PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, claims));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNullOrEmpty();
  }

  private void insertPolicyWithAgentCode(String policyNumber, String agentCode) {
    jdbcTemplate.update(
        """
        INSERT INTO pas.policies (
            policy_number, policy_type, policy_status, effective_date, expiry_date,
            policyholder_id, agent_code, branch_code, total_premium, deductible,
            coverage_limit, inception_date, renewal_count, uw_status, risk_score,
            web_indicator, api_flag, last_updated, updated_by
        )
        SELECT ?, policy_type, policy_status, effective_date, expiry_date,
               policyholder_id, CAST(? AS CHAR(6)), 'CHI1', total_premium, deductible,
               coverage_limit, inception_date, renewal_count, uw_status, risk_score,
               web_indicator, api_flag, last_updated, updated_by
        FROM pas.policies
        WHERE policy_number = 'POL-00000001'
        """,
        policyNumber,
        agentCode);
  }

  private static List<String> inquiryPaths() {
    return List.of(
        "/api/v1/policies/POL-00000001",
        "/api/v1/policies/POL-00000001/coverages",
        "/api/v1/policies/POL-00000001/policyholder");
  }

  private ResponseEntity<String> get(String path, String jwt) {
    HttpHeaders headers = new HttpHeaders();
    if (jwt != null) {
      headers.setBearerAuth(jwt);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
