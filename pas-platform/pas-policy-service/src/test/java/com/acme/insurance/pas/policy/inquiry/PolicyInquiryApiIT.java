package com.acme.insurance.pas.policy.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.TestPiiKeys;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.security.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AB-524 acceptance: the v1 inquiry API replays the facade seed data and matches {@code
 * compat/api/snapshots/policies-v1.json}; unauthenticated, wrong-scope and cross-tenant callers are
 * rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PolicyInquiryApiIT {

  static final Path SNAPSHOT = Path.of("../../compat/api/snapshots/policies-v1.json");
  static final String READ = PasScopes.POLICY_READ;
  static final String PII = PasScopes.POLICY_PII_READ;

  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestPiiKeys.register(registry);
    TestJwtIssuer.register(registry);
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PolicyHolderRepository holders;
  @Autowired private ObjectMapper mapper;

  private static boolean seeded;

  @BeforeEach
  void seedOnce() {
    if (!seeded) {
      FacadeSeed.load(jdbc, holders);
      seeded = true;
    }
  }

  // ---- contract ---------------------------------------------------------------------------

  @Test
  void responsesMatchFacadeSnapshot() throws Exception {
    JsonNode snapshot = mapper.readTree(Files.readString(SNAPSHOT));
    String bearer = TestJwtIssuer.bearer().scopes(READ).book("*").sign();
    assertThat(snapshot.size()).isGreaterThanOrEqualTo(8);
    for (Iterator<Map.Entry<String, JsonNode>> it = snapshot.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      ResponseEntity<String> res = get(entry.getKey(), bearer);
      JsonNode expected = entry.getValue();
      assertThat(res.getStatusCode().value())
          .as(entry.getKey())
          .isEqualTo(expected.get("status").asInt());
      if (expected.get("body").isNull()) {
        assertThat(res.getBody()).as(entry.getKey() + " body").isNullOrEmpty();
      } else {
        JsonNode actual = mapper.readTree(res.getBody());
        assertThat(SnapshotContract.compare(expected.get("body"), actual))
            .as(entry.getKey())
            .isEmpty();
      }
    }
  }

  @Test
  void policyFieldsAreExactlyTheFacadeFields() throws Exception {
    JsonNode body =
        mapper.readTree(
            get(
                    "/api/v1/policies/POL-00000001",
                    TestJwtIssuer.bearer().scopes(READ).book("*").sign())
                .getBody());
    assertThat(body.fieldNames())
        .toIterable()
        .containsExactly(
            "policyNumber",
            "policyType",
            "policyStatus",
            "effectiveDate",
            "expiryDate",
            "policyholderId",
            "agentCode",
            "branchCode",
            "totalPremium",
            "deductible",
            "coverageLimit",
            "inceptionDate",
            "renewalCount",
            "uwStatus",
            "riskScore",
            "webIndicator",
            "apiFlag",
            "lastUpdated",
            "updatedBy");
  }

  // ---- authentication (401) ------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/policies/POL-00000001",
        "/api/v1/policies/POL-00000001/coverages",
        "/api/v1/policies/POL-00000001/holder",
        "/api/v1/policies/POL-99999999",
        "/v3/api-docs",
        "/actuator/prometheus"
      })
  void unauthenticatedRequestsAreRejected(String path) {
    ResponseEntity<String> res = rest.getForEntity(path, String.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(res.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    assertThat(res.getBody()).isNullOrEmpty();
  }

  @Test
  void invalidTokensAreRejected() {
    String path = "/api/v1/policies/POL-00000001";
    assertThat(status(path, "not-a-jwt")).isEqualTo(401);
    assertThat(status(path, base().signWithUntrustedKey())).as("untrusted key").isEqualTo(401);
    assertThat(status(path, base().issuer("https://evil.example").sign())).as("iss").isEqualTo(401);
    assertThat(status(path, base().audience("other-api").sign())).as("aud").isEqualTo(401);
    assertThat(status(path, base().expiresAt(Instant.now().minusSeconds(600)).sign()))
        .as("expired")
        .isEqualTo(401);
  }

  // ---- scope (403) --------------------------------------------------------------------------

  @Test
  void tokenWithoutPolicyReadIsForbidden() {
    String bearer = TestJwtIssuer.bearer().scopes("rating:quote", PII).book("*").sign();
    for (String path :
        List.of(
            "/api/v1/policies/POL-00000001",
            "/api/v1/policies/POL-00000001/coverages",
            "/api/v1/policies/POL-00000001/holder")) {
      assertThat(status(path, bearer)).as(path).isEqualTo(403);
    }
  }

  // ---- object-level authorisation (cross-tenant → 404) ---------------------------------------

  @Test
  void agentSeesOnlyOwnPolicies() {
    String agent = TestJwtIssuer.bearer().scopes(READ).agent("AG1001").sign();
    assertThat(status("/api/v1/policies/POL-00000001", agent)).isEqualTo(200);
    assertThat(status("/api/v1/policies/POL-00000001/coverages", agent)).isEqualTo(200);
    assertThat(status("/api/v1/policies/POL-00000002", agent)).isEqualTo(404);
    assertThat(status("/api/v1/policies/POL-00000003", agent)).isEqualTo(404);
  }

  @Test
  void branchSeesBranchPolicies() {
    String branch = TestJwtIssuer.bearer().scopes(READ).branch("CHI1").sign();
    assertThat(status("/api/v1/policies/POL-00000001", branch)).isEqualTo(200);
    assertThat(status("/api/v1/policies/POL-00000002", branch)).isEqualTo(200);
    assertThat(status("/api/v1/policies/POL-00000003", branch)).isEqualTo(404);
  }

  @Test
  void bookOfBusinessEntriesGrantAccess() {
    String book = TestJwtIssuer.bearer().scopes(READ).book("branch:DET1", "agent:AG2005").sign();
    assertThat(status("/api/v1/policies/POL-00000001", book)).isEqualTo(404);
    assertThat(status("/api/v1/policies/POL-00000002", book)).isEqualTo(200);
    assertThat(status("/api/v1/policies/POL-00000003", book)).isEqualTo(200);
  }

  @Test
  void crossTenantIsIndistinguishableFromNotFound() {
    String other = TestJwtIssuer.bearer().scopes(READ, PII).agent("AG9999").branch("NYC1").sign();
    for (String suffix : List.of("", "/coverages", "/holder")) {
      ResponseEntity<String> denied = get("/api/v1/policies/POL-00000001" + suffix, other);
      ResponseEntity<String> missing = get("/api/v1/policies/POL-99999999" + suffix, other);
      assertThat(denied.getStatusCode()).as(suffix).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(denied.getBody()).as(suffix).isEqualTo(missing.getBody()).isNullOrEmpty();
      assertThat(denied.getHeaders().getContentType())
          .isEqualTo(missing.getHeaders().getContentType());
    }
  }

  @Test
  void securityHeadersAreWrittenOnEveryInquiryResponse() {
    String entitled = TestJwtIssuer.bearer().scopes(READ).agent("AG1001").sign();
    String noScope = TestJwtIssuer.bearer().scopes("openid").agent("AG1001").sign();
    List<ResponseEntity<String>> responses =
        List.of(
            get("/api/v1/policies/POL-00000001", entitled),
            get("/api/v1/policies/POL-00000001", null),
            get("/api/v1/policies/POL-00000001", noScope),
            get("/api/v1/policies/POL-00000003", entitled));
    assertThat(responses)
        .extracting(r -> r.getStatusCode().value())
        .containsExactly(200, 401, 403, 404);
    for (ResponseEntity<String> r : responses) {
      HttpHeaders h = r.getHeaders();
      assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
      assertThat(h.getCacheControl()).contains("no-store");
    }
  }

  @Test
  void tokenWithoutEntitlementClaimsSeesNothing() {
    String none = TestJwtIssuer.bearer().scopes(READ).sign();
    assertThat(status("/api/v1/policies/POL-00000001", none)).isEqualTo(404);
  }

  // ---- policyholder view / PII ---------------------------------------------------------------

  @Test
  void holderIsMaskedWithoutPiiScope() throws Exception {
    JsonNode body =
        mapper.readTree(
            get(
                    "/api/v1/policies/POL-00000001/holder",
                    TestJwtIssuer.bearer().scopes(READ).agent("AG1001").sign())
                .getBody());
    assertThat(body.get("masked").asBoolean()).isTrue();
    assertThat(body.get("custId").asText()).isEqualTo("C000000001");
    assertThat(body.get("lastName").asText()).isEqualTo("S***");
    assertThat(body.get("addrLine1").asText()).isEqualTo("[REDACTED]");
    assertThat(body.get("phone").asText()).isEqualTo("***-***-0100");
    assertThat(body.get("email").asText()).isEqualTo("j***@example.com");
    assertThat(body.get("dateOfBirth").asText()).isEqualTo("1975-**-**");
    assertThat(body.get("creditScore").asText()).isEqualTo("[REDACTED]");
    assertThat(body.toString())
        .doesNotContain("Smith", "123 Main", "Springfield", "217-555", "1975-06-15", "720");
  }

  @Test
  void holderTaxIdIsMaskedWithoutPiiScope() throws Exception {
    JsonNode body =
        mapper.readTree(
            get(
                    "/api/v1/policies/POL-00000003/holder",
                    TestJwtIssuer.bearer().scopes(READ).branch("DET1").sign())
                .getBody());
    assertThat(body.get("taxId").asText()).isEqualTo("********67");
    assertThat(body.toString()).doesNotContain("38-1234567");
  }

  @Test
  void holderIsUnmaskedWithPiiScope() throws Exception {
    JsonNode body =
        mapper.readTree(
            get(
                    "/api/v1/policies/POL-00000001/holder",
                    TestJwtIssuer.bearer().scopes(READ, PII).agent("AG1001").sign())
                .getBody());
    assertThat(body.get("masked").asBoolean()).isFalse();
    assertThat(body.get("lastName").asText()).isEqualTo("Smith");
    assertThat(body.get("dateOfBirth").asText()).isEqualTo("1975-06-15");
    assertThat(body.get("creditScore").asText()).isEqualTo("720");
  }

  @Test
  void policyResponsesNeverCarryHolderPii() {
    String body =
        get(
                "/api/v1/policies/POL-00000001",
                TestJwtIssuer.bearer().scopes(READ, PII).book("*").sign())
            .getBody();
    assertThat(body).doesNotContain("Smith", "1975-06-15", "217-555-0100");
  }

  private static TestJwtIssuer.Builder base() {
    return TestJwtIssuer.bearer().scopes(READ).book("*");
  }

  private int status(String path, String bearer) {
    return get(path, bearer).getStatusCode().value();
  }

  private ResponseEntity<String> get(String path, String bearer) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
