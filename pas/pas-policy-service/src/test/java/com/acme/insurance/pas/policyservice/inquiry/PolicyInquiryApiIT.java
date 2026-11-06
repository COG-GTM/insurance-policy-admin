package com.acme.insurance.pas.policyservice.inquiry;

import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICYHOLDER_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * AB-524 acceptance: unauthenticated and cross-tenant requests are rejected, and personal data is
 * only returned to callers allowed to see it. Runs against the facade's seed data.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@Sql(
    scripts = "classpath:contract/facade-seed.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PolicyInquiryApiIT {

  private static final List<String> ENDPOINTS =
      List.of(
          "/api/v1/policies/POL-00000001",
          "/api/v1/policies/POL-00000001/coverages",
          "/api/v1/policies/POL-00000001/policyholder");

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @LocalServerPort int port;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  @Test
  void requestsWithoutATokenAreRejected() throws Exception {
    for (String path : ENDPOINTS) {
      HttpResponse<String> response = get(path, Map.of());
      assertThat(response.statusCode()).as(path).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .get()
          .asString()
          .startsWith("Bearer");
      assertThat(response.body()).as(path).doesNotContain("POL-00000001");
    }
  }

  @Test
  void malformedAndUntrustedTokensAreRejected() throws Exception {
    Instant past = Instant.now().minusSeconds(3600);
    Map<String, String> tokens =
        Map.of(
            "garbage", "not-a-jwt",
            "untrusted key",
                TestJwt.signWithUntrustedKey(
                    TestJwt.claims(POLICY_READ).claim("pas_book", "*").build()),
            "wrong issuer",
                TestJwt.sign(
                    TestJwt.claims(POLICY_READ)
                        .claim("pas_book", "*")
                        .issuer("https://evil.invalid")
                        .build()),
            "wrong audience",
                TestJwt.sign(
                    TestJwt.claims(POLICY_READ)
                        .claim("pas_book", "*")
                        .audience("some-other-api")
                        .build()),
            "expired",
                TestJwt.sign(
                    TestJwt.claims(POLICY_READ)
                        .claim("pas_book", "*")
                        .issueTime(Date.from(past.minusSeconds(600)))
                        .expirationTime(Date.from(past))
                        .build()),
            "no expiry",
                TestJwt.sign(
                    TestJwt.claims(POLICY_READ)
                        .claim("pas_book", "*")
                        .expirationTime(null)
                        .build()));
    for (Map.Entry<String, String> token : tokens.entrySet()) {
      for (String path : ENDPOINTS) {
        assertThat(get(path, TestJwt.bearer(token.getValue())).statusCode())
            .as(token.getKey() + " " + path)
            .isEqualTo(401);
      }
    }
  }

  @Test
  void tokensWithoutThePolicyReadScopeAreForbidden() throws Exception {
    String jwt = TestJwt.wholeBook("pas.batch.operate " + POLICYHOLDER_READ);
    for (String path : ENDPOINTS) {
      assertThat(get(path, TestJwt.bearer(jwt)).statusCode()).as(path).isEqualTo(403);
    }
  }

  @Test
  void anAgentSeesOnlyTheirOwnPolicies() throws Exception {
    String jwt = TestJwt.agents(POLICY_READ, "AG1001");
    assertThat(get("/api/v1/policies/POL-00000001", TestJwt.bearer(jwt)).statusCode())
        .isEqualTo(200);
    for (String policy : new String[] {"POL-00000002", "POL-00000003"}) {
      assertCrossTenantLooksLikeNotFound(jwt, policy);
    }
  }

  @Test
  void aBranchSeesOnlyItsBranchPolicies() throws Exception {
    String jwt = TestJwt.branches(POLICY_READ, "CHI1");
    for (String policy : new String[] {"POL-00000001", "POL-00000002"}) {
      assertThat(get("/api/v1/policies/" + policy, TestJwt.bearer(jwt)).statusCode())
          .as(policy)
          .isEqualTo(200);
    }
    assertCrossTenantLooksLikeNotFound(jwt, "POL-00000003");
  }

  @Test
  void theBrokerPortalListShowsOnlyEntitledPoliciesPageByPage() throws Exception {
    Map<String, String> branch = TestJwt.bearer(TestJwt.branches(POLICY_READ, "CHI1"));
    JsonNode first = json.readTree(get("/api/v1/policies?branchCode=CHI1&limit=1", branch).body());
    assertThat(first.get("policies")).hasSize(1);
    assertThat(first.at("/policies/0/policyNumber").asText()).isEqualTo("POL-00000001");
    assertThat(first.get("nextAfter").asText()).isEqualTo("POL-00000001");
    JsonNode second =
        json.readTree(
            get("/api/v1/policies?branchCode=CHI1&limit=1&after=POL-00000001", branch).body());
    assertThat(second.at("/policies/0/policyNumber").asText()).isEqualTo("POL-00000002");

    Map<String, String> agent = TestJwt.bearer(TestJwt.agents(POLICY_READ, "AG1001"));
    JsonNode own = json.readTree(get("/api/v1/policies?agentCode=AG1001", agent).body());
    assertThat(own.get("policies"))
        .extracting(p -> p.get("agentCode").asText())
        .containsOnly("AG1001");
    JsonNode other = json.readTree(get("/api/v1/policies?branchCode=NYC1", agent).body());
    assertThat(other.get("policies")).isEmpty();
    assertThat(get("/api/v1/policies?agentCode=AG1001&branchCode=CHI1", agent).statusCode())
        .isEqualTo(400);
    assertThat(get("/api/v1/policies?agentCode=x%27or", agent).statusCode()).isEqualTo(400);
  }

  @Test
  void aTokenWithoutAnyEntitlementSeesNothing() throws Exception {
    String jwt = TestJwt.sign(TestJwt.claims(POLICY_READ).build());
    assertCrossTenantLooksLikeNotFound(jwt, "POL-00000001");
  }

  @Test
  void aCrossTenantDenialIsIndistinguishableFromAMissingPolicy() throws Exception {
    String jwt = TestJwt.agents(POLICY_READ, "AG1001");
    HttpResponse<String> denied = get("/api/v1/policies/POL-00000003", TestJwt.bearer(jwt));
    HttpResponse<String> missing = get("/api/v1/policies/POL-99999999", TestJwt.bearer(jwt));
    assertThat(denied.statusCode()).isEqualTo(missing.statusCode()).isEqualTo(404);
    assertThat(denied.body()).isEqualTo(missing.body()).isEmpty();
  }

  @Test
  void policyholderIsMaskedWithoutThePolicyholderScope() throws Exception {
    JsonNode holder =
        json.readTree(
            get(
                    "/api/v1/policies/POL-00000001/policyholder",
                    TestJwt.bearer(TestJwt.wholeBook(POLICY_READ)))
                .body());
    assertThat(holder.get("custId").asText()).isEqualTo("C000000001");
    assertThat(holder.get("masked").asBoolean()).isTrue();
    assertThat(holder.get("lastName").asText()).isEqualTo("S***");
    assertThat(holder.get("firstName").asText()).isEqualTo("J***");
    assertThat(holder.get("email").asText()).isEqualTo("***@example.com");
    assertThat(holder.get("phone").isNull()).isTrue();
    assertNoRestrictedFields(holder);
  }

  @Test
  void policyholderIsInClearWithThePolicyholderScope() throws Exception {
    JsonNode holder =
        json.readTree(
            get(
                    "/api/v1/policies/POL-00000003/policyholder",
                    TestJwt.bearer(TestJwt.wholeBook(POLICY_READ + " " + POLICYHOLDER_READ)))
                .body());
    assertThat(holder.get("masked").asBoolean()).isFalse();
    assertThat(holder.get("custType").asText()).isEqualTo("C");
    assertThat(holder.get("companyName").asText()).isEqualTo("Acme Manufacturing LLC");
    assertThat(holder.get("phone").asText()).isEqualTo("313-555-0300");
    assertNoRestrictedFields(holder);
  }

  @Test
  void policyholderScopeDoesNotWidenTheEntitlement() throws Exception {
    String jwt = TestJwt.agents(POLICY_READ + " " + POLICYHOLDER_READ, "AG1001");
    assertThat(get("/api/v1/policies/POL-00000003/policyholder", TestJwt.bearer(jwt)).statusCode())
        .isEqualTo(404);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/policies/POL-000000011234",
        "/api/v1/policies/x'%20OR%20'1'='1",
        "/api/v1/policies/POL-99999999/coverages",
        "/api/v1/policies/POL-99999999/policyholder"
      })
  void unknownOrInvalidPolicyNumbersAreNotFound(String path) throws Exception {
    HttpResponse<String> response = get(path, TestJwt.bearer(TestJwt.wholeBook(POLICY_READ)));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).isEmpty();
  }

  @Test
  void otherMethodsAndPathsStayDenied() throws Exception {
    Map<String, String> auth = TestJwt.bearer(TestJwt.wholeBook(POLICY_READ));
    assertThat(get("/api/v1/policies", auth).statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/ping", auth).statusCode()).isEqualTo(403);
    HttpRequest.Builder delete =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + ENDPOINTS.get(0)))
            .DELETE()
            .header("Authorization", auth.get("Authorization"));
    assertThat(http.send(delete.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(403);
  }

  private void assertCrossTenantLooksLikeNotFound(String jwt, String policy) throws Exception {
    for (String suffix : new String[] {"", "/coverages", "/policyholder"}) {
      HttpResponse<String> response =
          get("/api/v1/policies/" + policy + suffix, TestJwt.bearer(jwt));
      assertThat(response.statusCode()).as(policy + suffix).isEqualTo(404);
      assertThat(response.body()).as(policy + suffix).isEmpty();
    }
  }

  private static void assertNoRestrictedFields(JsonNode holder) {
    for (String field :
        new String[] {"dateOfBirth", "ssnLast4", "taxId", "creditScore", "addrLine1", "zipCode"}) {
      assertThat(holder.has(field)).as(field).isFalse();
    }
    assertThat(holder.toString()).doesNotContain("1975").doesNotContain("38-1234567");
  }

  @Test
  void dpopRequestsAreRejectedBeforeAuthentication() throws Exception {
    String token = TestJwt.wholeBook(POLICY_READ);
    List<Map<String, String>> requests =
        List.of(
            Map.of("Authorization", "DPoP " + token, "DPoP", token),
            Map.of("Authorization", "dpop " + token),
            Map.of("Authorization", "Bearer " + token, "DPoP", token));
    for (Map<String, String> headers : requests) {
      HttpResponse<String> response = get("/api/v1/policies/POL-00000001", headers);
      assertThat(response.statusCode()).as(headers.keySet().toString()).isEqualTo(401);
      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .get()
          .asString()
          .startsWith("Bearer")
          .contains("DPoP is not supported");
      assertThat(response.body()).isEmpty();
    }
  }

  private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    headers.forEach(request::header);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
