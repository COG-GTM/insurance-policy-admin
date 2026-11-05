package com.acme.insurance.pas.policyservice.change;

import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_ENDORSE;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_RENEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.persistence.EndorsementJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyChangeAudit;
import com.acme.insurance.pas.persistence.PolicyChangeAuditJdbcRepository;
import com.acme.insurance.pas.policyservice.TestJwt;
import com.acme.insurance.pas.policyservice.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AB-526 acceptance through HTTP against PostgreSQL: renewal and endorsement writes, Feb 29 and
 * zero-length terms, object-level authorization, atomicity and concurrent endorsements of one
 * policy. Every test uses its own policy, so tests and other ITs on the shared database don't mix.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "pas.policy-change.endorsement-premium-change-enabled=true"
    })
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class PolicyChangeApiIT {

  private static final AtomicInteger NEXT =
      new AtomicInteger(java.util.concurrent.ThreadLocalRandom.current().nextInt(90_000));
  private static final String ALL = POLICY_RENEW + " " + POLICY_ENDORSE;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired EndorsementJdbcRepository endorsements;
  @Autowired PolicyChangeAuditJdbcRepository audit;

  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    TestJwt.register(registry);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** Inserts a policy of agent AG7001 / branch BR71 with one active and one cancelled coverage. */
  private String policy(String status, LocalDate effective, LocalDate expiry, String premium) {
    String number = String.format("POL-526%05d", NEXT.incrementAndGet());
    jdbc.sql("DELETE FROM acmeins.endorsements WHERE policy_number = ?").param(number).update();
    jdbc.sql("DELETE FROM acmeins.coverages WHERE policy_number = ?").param(number).update();
    jdbc.sql("DELETE FROM acmeins.policies WHERE policy_number = ?").param(number).update();
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
                expiry_date, policyholder_id, agent_code, branch_code, total_premium,
                renewal_count, uw_status)
            VALUES (?, 'AUT', ?, ?, ?, 'C000000001', 'AG7001', 'BR71', ?, 2, 'AP')
            """)
        .params(number, status, effective, expiry, new BigDecimal(premium))
        .update();
    for (Object[] c : new Object[][] {{1, "AC"}, {2, "CN"}}) {
      jdbc.sql(
              """
              INSERT INTO acmeins.coverages (policy_number, sequence_num, coverage_type,
                  premium, effective_date, expiry_date, status)
              VALUES (?, ?, 'BI', 10.00, ?, ?, ?)
              """)
          .params(number, c[0], effective, expiry, c[1])
          .update();
    }
    return number;
  }

  private HttpResponse<String> post(String path, String body, String jwt) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (jwt != null) {
      request.header("Authorization", "Bearer " + jwt);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> renew(String policy, String body, String jwt) throws Exception {
    return post("/api/v1/policies/" + policy + "/renewals", body, jwt);
  }

  private HttpResponse<String> endorse(String policy, String body, String jwt) throws Exception {
    return post("/api/v1/policies/" + policy + "/endorsements", body, jwt);
  }

  private Map<String, Object> row(String policy) {
    return jdbc.sql("SELECT * FROM acmeins.policies WHERE policy_number = ?")
        .param(policy)
        .query()
        .singleRow();
  }

  @Test
  void renewalWritesTheNewTermPremiumCoveragesAndAudit() throws Exception {
    String policy = policy("AC", LocalDate.of(2024, 3, 1), LocalDate.of(2025, 3, 1), "1234.57");

    HttpResponse<String> response = renew(policy, "", TestJwt.agents(ALL, "AG7001"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("totalPremium").decimalValue()).isEqualByComparingTo("1296.29");
    assertThat(body.get("expiryDate").asText()).isEqualTo("2026-03-01");
    assertThat(body.get("renewalCount").asInt()).isEqualTo(3);
    Map<String, Object> row = row(policy);
    assertThat(row.get("effective_date").toString()).isEqualTo("2025-03-01");
    assertThat(row.get("expiry_date").toString()).isEqualTo("2026-03-01");
    assertThat((BigDecimal) row.get("total_premium")).isEqualByComparingTo("1296.29");
    assertThat(row.get("uw_status")).isEqualTo("PN");
    assertThat(row.get("updated_by").toString().strip()).isEqualTo("POLRNW");
    List<Map<String, Object>> coverages =
        jdbc.sql("SELECT expiry_date FROM acmeins.coverages WHERE policy_number = ? ORDER BY 1")
            .param(policy)
            .query()
            .listOfRows();
    assertThat(coverages)
        .extracting(c -> c.get("expiry_date").toString())
        .containsExactly("2025-03-01", "2026-03-01");
    List<PolicyChangeAudit> trail = audit.findByPolicy(policy);
    assertThat(trail)
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.operation()).isEqualTo(PolicyChangeAudit.Operation.RENEWAL);
              assertThat(a.principal()).isEqualTo("test-user");
              assertThat(a.clientId()).isEqualTo("test-client");
              assertThat(a.premiumBefore()).isEqualByComparingTo("1234.57");
              assertThat(a.premiumAfter()).isEqualByComparingTo("1296.29");
            });
  }

  @Test
  void feb29ExpiryRenewsTo28February() throws Exception {
    String policy = policy("EX", LocalDate.of(2023, 2, 28), LocalDate.of(2024, 2, 29), "1000.00");

    HttpResponse<String> response = renew(policy, "{}", TestJwt.wholeBook(POLICY_RENEW));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(row(policy).get("effective_date").toString()).isEqualTo("2024-02-29");
    assertThat(row(policy).get("expiry_date").toString()).isEqualTo("2025-02-28");
  }

  @Test
  void ineligibleAndStaleRenewalsWriteNothing() throws Exception {
    String cancelled = policy("CN", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), "100.00");
    String active = policy("AC", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), "100.00");
    String jwt = TestJwt.wholeBook(POLICY_RENEW);

    HttpResponse<String> refused = renew(cancelled, "", jwt);
    HttpResponse<String> stale = renew(active, "{\"expectedExpiryDate\":\"2024-06-01\"}", jwt);
    HttpResponse<String> retried = renew(active, "{\"expectedExpiryDate\":\"2025-01-01\"}", jwt);
    HttpResponse<String> again = renew(active, "{\"expectedExpiryDate\":\"2025-01-01\"}", jwt);

    assertThat(refused.statusCode()).isEqualTo(422);
    assertThat(json.readTree(refused.body()).get("code").asText())
        .isEqualTo("NOT_ELIGIBLE_FOR_RENEWAL");
    assertThat(json.readTree(refused.body()).get("detail").asText())
        .isEqualTo("POLICY NOT ELIGIBLE FOR RENEWAL");
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(retried.statusCode()).isEqualTo(200);
    assertThat(again.statusCode()).as("a retried renewal does not renew twice").isEqualTo(409);
    assertThat(audit.findByPolicy(cancelled)).isEmpty();
    assertThat(audit.findByPolicy(active)).hasSize(1);
  }

  @Test
  void endorsementInsertsTheRowAddsTheProRataAdjustmentAndAudits() throws Exception {
    LocalDate start = today().minusDays(100);
    String policy = policy("AC", start, start.plusDays(400), "1000.00");

    HttpResponse<String> response =
        endorse(
            policy,
            "{\"endorsementType\":\"LCH\",\"description\":\"limit up\","
                + "\"annualPremiumChange\":400.00}",
            TestJwt.branches(POLICY_ENDORSE, "BR71"));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("endorsementSeq").asInt()).isEqualTo(1);
    assertThat(body.get("daysInTerm").asLong()).isEqualTo(400);
    assertThat(body.get("daysRemaining").asLong()).isEqualTo(300);
    assertThat(body.get("proRataFactor").decimalValue()).isEqualByComparingTo("0.75");
    assertThat(body.get("premiumAdjustment").decimalValue()).isEqualByComparingTo("300.00");
    assertThat((BigDecimal) row(policy).get("total_premium")).isEqualByComparingTo("1300.00");
    assertThat(endorsements.findByPolicy(policy))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.endorsementTypeCode()).isEqualTo("LCH");
              assertThat(e.effectiveDate()).isEqualTo(today());
              assertThat(e.processedBy()).isEqualTo("POLEND");
            });
    assertThat(audit.findByPolicy(policy))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.operation()).isEqualTo(PolicyChangeAudit.Operation.ENDORSEMENT);
              assertThat(a.endorsementSeq()).isEqualTo(1);
              assertThat(a.premiumAdjustment()).isEqualByComparingTo("300.00");
            });
  }

  @Test
  void endorsementWithoutAPremiumChangeAdjustsByZeroLikePolend() throws Exception {
    LocalDate start = today().minusDays(10);
    String policy = policy("AC", start, start.plusYears(1), "500.00");

    HttpResponse<String> response =
        endorse(policy, "{\"endorsementType\":\"CAD\"}", TestJwt.wholeBook(POLICY_ENDORSE));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(json.readTree(response.body()).get("premiumAdjustment").decimalValue())
        .isEqualByComparingTo("0.00");
    assertThat((BigDecimal) row(policy).get("total_premium")).isEqualByComparingTo("500.00");
  }

  @Test
  void zeroLengthTermUsesAFactorOfOne() throws Exception {
    String policy = policy("AC", today(), today(), "100.00");

    HttpResponse<String> response =
        endorse(
            policy,
            "{\"endorsementType\":\"ACH\",\"annualPremiumChange\":25.50}",
            TestJwt.wholeBook(POLICY_ENDORSE));

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode body = json.readTree(response.body());
    assertThat(body.get("daysInTerm").asLong()).isZero();
    assertThat(body.get("proRataFactor").decimalValue()).isEqualByComparingTo("1");
    assertThat((BigDecimal) row(policy).get("total_premium")).isEqualByComparingTo("125.50");
  }

  @Test
  void endorsementTypeIsRequiredAndMustBeKnown() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");
    String jwt = TestJwt.wholeBook(POLICY_ENDORSE);

    for (String body :
        new String[] {
          "{}",
          "{\"endorsementType\":\"   \"}",
          "{\"endorsementType\":\"XYZ\"}",
          "{\"endorsementType\":\"CAD \"}",
          "{\"endorsementType\":\"CAD\",\"annualPremiumChange\":1.001}",
          "not json"
        }) {
      assertThat(endorse(policy, body, jwt).statusCode()).as(body).isEqualTo(400);
    }
    assertThat(endorsements.findByPolicy(policy)).isEmpty();
    assertThat(audit.findByPolicy(policy)).isEmpty();
  }

  @Test
  void auditKeepsTheClientIdAsIssued() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");
    String jwt =
        TestJwt.sign(
            TestJwt.claims(POLICY_ENDORSE)
                .claim("azp", "broker/portal")
                .claim("pas_book", "*")
                .build());

    assertThat(endorse(policy, "{\"endorsementType\":\"CAD\"}", jwt).statusCode()).isEqualTo(201);
    assertThat(audit.findByPolicy(policy))
        .singleElement()
        .satisfies(a -> assertThat(a.clientId()).isEqualTo("broker/portal"));
  }

  @Test
  void anOverlongSubjectIsAuditedCutWithItsDigest() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");
    String subject = "u".repeat(300);
    String jwt =
        TestJwt.sign(
            TestJwt.claims(POLICY_ENDORSE).subject(subject).claim("pas_book", "*").build());

    assertThat(endorse(policy, "{\"endorsementType\":\"CAD\"}", jwt).statusCode()).isEqualTo(201);
    assertThat(audit.findByPolicy(policy))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.principal()).hasSize(Actor.PRINCIPAL_BYTES).contains("#sha256:");
              assertThat(a.principal()).isEqualTo(Actor.auditPrincipal(subject));
            });
  }

  @Test
  void onlyActivePoliciesAreEndorsed() throws Exception {
    String policy = policy("EX", today().minusYears(1), today().minusDays(1), "100.00");

    HttpResponse<String> response =
        endorse(policy, "{\"endorsementType\":\"CAD\"}", TestJwt.wholeBook(POLICY_ENDORSE));

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(json.readTree(response.body()).get("code").asText())
        .isEqualTo("NOT_ACTIVE_FOR_ENDORSEMENT");
    assertThat(endorsements.findByPolicy(policy)).isEmpty();
  }

  @Test
  void objectLevelAuthorizationHidesOtherBooksAndWritesNothing() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");
    List<String> outsiders =
        List.of(
            TestJwt.agents(ALL, "AG1001"),
            TestJwt.branches(ALL, "CHI1"),
            TestJwt.sign(TestJwt.claims(ALL).build()));

    for (String jwt : outsiders) {
      HttpResponse<String> renewal = renew(policy, "", jwt);
      HttpResponse<String> endorsement = endorse(policy, "{\"endorsementType\":\"CAD\"}", jwt);
      HttpResponse<String> missing = renew("POL-99999999", "", jwt);
      assertThat(renewal.statusCode()).isEqualTo(404);
      assertThat(endorsement.statusCode()).isEqualTo(404);
      assertThat(renewal.body()).isEqualTo(missing.body()).isEmpty();
    }
    assertThat(((Number) row(policy).get("renewal_count")).intValue()).isEqualTo(2);
    assertThat(endorsements.findByPolicy(policy)).isEmpty();
    assertThat(audit.findByPolicy(policy)).isEmpty();
  }

  @Test
  void eachOperationNeedsItsOwnScopeAndAToken() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");

    assertThat(renew(policy, "", null).statusCode()).isEqualTo(401);
    assertThat(
            renew(policy, "", TestJwt.wholeBook(POLICY_READ + " " + POLICY_ENDORSE)).statusCode())
        .isEqualTo(403);
    assertThat(
            endorse(
                    policy,
                    "{\"endorsementType\":\"CAD\"}",
                    TestJwt.wholeBook(POLICY_READ + " " + POLICY_RENEW))
                .statusCode())
        .isEqualTo(403);
  }

  @Test
  void aFailedAuditWriteRollsBackTheWholeEndorsement() throws Exception {
    String policy = policy("AC", today().minusDays(1), today().plusDays(300), "100.00");
    String trigger = "fail_audit_" + policy.replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    jdbc.sql(
            "CREATE FUNCTION pasapp."
                + trigger
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit"
                + " unavailable' USING ERRCODE = 'P0001'; END $$")
        .update();
    jdbc.sql(
            "CREATE TRIGGER "
                + trigger
                + " BEFORE INSERT ON pasapp.policy_change_audit FOR EACH ROW WHEN"
                + " (NEW.policy_number = '"
                + policy
                + "') EXECUTE FUNCTION pasapp."
                + trigger
                + "()")
        .update();
    try {
      HttpResponse<String> response =
          endorse(
              policy,
              "{\"endorsementType\":\"CAD\",\"annualPremiumChange\":50.00}",
              TestJwt.wholeBook(POLICY_ENDORSE));

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(endorsements.findByPolicy(policy)).isEmpty();
      assertThat((BigDecimal) row(policy).get("total_premium")).isEqualByComparingTo("100.00");
    } finally {
      jdbc.sql("DROP TRIGGER " + trigger + " ON pasapp.policy_change_audit").update();
      jdbc.sql("DROP FUNCTION pasapp." + trigger + "()").update();
    }
  }

  @Test
  void concurrentEndorsementsOfOnePolicyAreSerialisedByTheRowLock() throws Exception {
    LocalDate start = today().minusDays(100);
    String policy = policy("AC", start, start.plusDays(400), "1000.00");
    String jwt = TestJwt.wholeBook(POLICY_ENDORSE);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        calls.add(
            () ->
                endorse(policy, "{\"endorsementType\":\"CRM\",\"annualPremiumChange\":40.00}", jwt)
                    .statusCode());
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : pool.invokeAll(calls)) {
        statuses.add(f.get());
      }
      assertThat(statuses).containsOnly(201);
    } finally {
      pool.shutdownNow();
    }

    assertThat(endorsements.findByPolicy(policy))
        .extracting(e -> e.endorsementSeq())
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    // 40.00 x 300/400 = 30.00 each; no lost update.
    assertThat((BigDecimal) row(policy).get("total_premium")).isEqualByComparingTo("1240.00");
    assertThat(audit.findByPolicy(policy))
        .extracting(PolicyChangeAudit::premiumBefore)
        .map(BigDecimal::stripTrailingZeros)
        .map(BigDecimal::toPlainString)
        .containsExactly("1000", "1030", "1060", "1090", "1120", "1150", "1180", "1210");
  }
}
