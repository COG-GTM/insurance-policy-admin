package com.acme.insurance.pas.policy.lifecycle;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** AB-526: renewals (POLRNW) and endorsements (POLEND) end to end against PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RenewalEndorsementApiIT {

  static final String HOLDER = "LCHOLD0001";

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
    if (!seeded) {
      WriteApiSupport.holder(holders, HOLDER, 700, "S");
      seeded = true;
    }
  }

  private void policy(String number, String status, LocalDate effective, LocalDate expiry) {
    WriteApiSupport.policy(jdbc, number, HOLDER, "AG0001", "BR01", status, "1000.00", "100000.00");
    jdbc.update(
        "UPDATE pas.policies SET effective_date = ?, expiry_date = ? WHERE policy_number = ?",
        effective,
        expiry,
        number);
  }

  private void coverage(String number, int seq, String status, LocalDate eff, LocalDate exp) {
    jdbc.update(
        "INSERT INTO pas.coverages (policy_number, sequence_num, coverage_type, effective_date,"
            + " expiry_date, status) VALUES (?, ?, 'BI', ?, ?, ?)",
        number,
        seq,
        eff,
        exp,
        status);
  }

  private static String writer() {
    return TestJwtIssuer.bearer()
        .subject("agent-ann")
        .scopes(PasScopes.POLICY_WRITE, PasScopes.POLICY_READ)
        .agent("AG0001")
        .sign();
  }

  private ResponseEntity<String> renew(String number, String bearer) {
    return send(
        rest, HttpMethod.POST, "/api/v1/policies/" + number + "/renewals", bearer, null, Map.of());
  }

  private ResponseEntity<String> endorse(String number, String bearer, String type) {
    String body = type == null ? "{}" : "{\"endorsementType\":\"" + type + "\"}";
    return send(
        rest,
        HttpMethod.POST,
        "/api/v1/policies/" + number + "/endorsements",
        bearer,
        body,
        Map.of());
  }

  private Map<String, Object> policyRow(String number) {
    return jdbc.queryForMap(
        "SELECT policy_status, uw_status, effective_date, expiry_date, total_premium,"
            + " renewal_count, updated_by, version FROM pas.policies WHERE policy_number = ?",
        number);
  }

  private int count(String table, String number) {
    String column = "pas.outbox".equals(table) ? "message_key" : "policy_number";
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, number);
  }

  private JsonNode lastEvent(String number) throws Exception {
    return mapper.readTree(
        jdbc.queryForObject(
            "SELECT payload FROM pas.outbox WHERE message_key = ? ORDER BY outbox_id DESC LIMIT 1",
            String.class,
            number));
  }

  // ---------------------------------------------------------------- renewal

  @Test
  void feb29TermRenewsToFeb28AndRedatesActiveCoverages() throws Exception {
    String n = "POLLC0000001";
    policy(n, "AC", LocalDate.of(2023, 3, 1), LocalDate.of(2024, 2, 29));
    coverage(n, 1, "AC", LocalDate.of(2023, 3, 1), LocalDate.of(2024, 2, 29));
    coverage(n, 2, "CN", LocalDate.of(2023, 3, 1), LocalDate.of(2023, 6, 1));

    ResponseEntity<String> res = renew(n, writer());
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode r = mapper.readTree(res.getBody());
    assertThat(r.get("effectiveDate").asText()).isEqualTo("2024-02-29");
    assertThat(r.get("expiryDate").asText()).isEqualTo("2025-02-28");
    assertThat(r.get("newPremium").decimalValue()).isEqualByComparingTo("1050.00");
    assertThat(r.get("rateChangePct").decimalValue()).isEqualByComparingTo("5.00");
    assertThat(r.get("rateCapPct").decimalValue()).isEqualByComparingTo("15");
    assertThat(r.get("rateCapApplied").asBoolean()).isFalse();
    assertThat(r.get("renewalCount").asInt()).isEqualTo(1);
    assertThat(r.get("coveragesRedated").asInt()).isEqualTo(1);
    assertThat(r.get("policyVersion").asLong()).isEqualTo(1);

    Map<String, Object> p = policyRow(n);
    assertThat(p.get("policy_status")).isEqualTo("AC");
    assertThat(p.get("uw_status")).isEqualTo("PN");
    assertThat(p.get("expiry_date").toString()).isEqualTo("2025-02-28");
    assertThat((BigDecimal) p.get("total_premium")).isEqualByComparingTo("1050.00");
    assertThat(((Number) p.get("renewal_count")).intValue()).isEqualTo(1);
    assertThat(((String) p.get("updated_by")).strip()).isEqualTo("POLRNW");
    assertThat(((Number) p.get("version")).longValue()).isEqualTo(1);

    List<Map<String, Object>> covs =
        jdbc.queryForList(
            "SELECT effective_date, expiry_date FROM pas.coverages WHERE policy_number = ?"
                + " ORDER BY sequence_num",
            n);
    assertThat(covs.get(0).get("effective_date").toString()).isEqualTo("2024-02-29");
    assertThat(covs.get(0).get("expiry_date").toString()).isEqualTo("2025-02-28");
    assertThat(covs.get(1).get("expiry_date").toString()).isEqualTo("2023-06-01");

    JsonNode event = lastEvent(n);
    assertThat(event.get("eventType").asText()).isEqualTo("PolicyRenewed");
    assertThat(event.get("policyVersion").asLong()).isEqualTo(1);
    assertThat(event.at("/data/expiryDate").asText()).isEqualTo("2025-02-28");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM pas.outbox WHERE message_key = ?", String.class, n))
        .isEqualTo("pas.policy.events.v1");
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, program, policy_version, actor FROM pas.policy_audit"
                + " WHERE policy_number = ?",
            n);
    assertThat(audit.get("action")).isEqualTo("RENEWAL");
    assertThat(audit.get("program")).isEqualTo("POLRNW");
    assertThat(((Number) audit.get("policy_version")).longValue()).isEqualTo(1);
    assertThat(audit.get("actor")).isEqualTo("agent-ann");
  }

  @Test
  void moreThanThreeOpenClaimsIsIneligibleAndChangesNothing() throws Exception {
    String n = "POLLC0000002";
    policy(n, "AC", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
    for (int i = 0; i < 4; i++) {
      claims.add(new Claim(n, today().minusMonths(1), "OPEN", new BigDecimal("10.00")));
    }
    ResponseEntity<String> res = renew(n, writer());
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    JsonNode err = mapper.readTree(res.getBody());
    assertThat(err.get("code").asText()).isEqualTo("RENEWAL_NOT_ELIGIBLE");
    assertThat(err.get("message").asText()).isEqualTo(RenewalService.MSG_OPEN_CLAIMS);
    assertThat(((Number) policyRow(n).get("version")).longValue()).isZero();
    assertThat(count("pas.outbox", n)).isZero();
    assertThat(count("pas.policy_audit", n)).isZero();

    claims.clear();
    for (int i = 0; i < 3; i++) {
      claims.add(new Claim(n, today().minusMonths(1), "OPEN", new BigDecimal("10.00")));
    }
    assertThat(renew(n, writer()).getStatusCode().value()).isEqualTo(201);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CN", "PN", "LP"})
  void onlyActiveOrExpiredPoliciesRenew(String status) throws Exception {
    String n = "POLLC00003" + status;
    policy(n, status, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
    ResponseEntity<String> res = renew(n, writer());
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(res.getBody()).get("message").asText())
        .isEqualTo(RenewalService.MSG_NOT_ELIGIBLE);
  }

  @Test
  void sentinelExpiryThatWrapsIsRefusedAndChangesNothing() throws Exception {
    String n = "POLLC0000005";
    policy(n, "AC", LocalDate.of(2025, 1, 1), LocalDate.of(9999, 12, 31));
    ResponseEntity<String> res = renew(n, writer());
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(res.getBody()).get("code").asText())
        .isEqualTo("RENEWAL_TERM_INVALID");
    Map<String, Object> p = policyRow(n);
    assertThat(p.get("expiry_date").toString()).isEqualTo("9999-12-31");
    assertThat(((Number) p.get("version")).longValue()).isZero();
    assertThat(count("pas.outbox", n)).isZero();
    assertThat(count("pas.policy_audit", n)).isZero();
  }

  @Test
  void expiryBeforeCobolDateRangeIsRefusedAndChangesNothing() throws Exception {
    String n = "POLLC0000006";
    policy(n, "AC", LocalDate.of(1600, 1, 1), LocalDate.of(1600, 12, 31));
    ResponseEntity<String> res = renew(n, writer());
    assertThat(res.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(res.getBody()).get("code").asText())
        .isEqualTo("RENEWAL_TERM_INVALID");
    assertThat(((Number) policyRow(n).get("version")).longValue()).isZero();
    assertThat(count("pas.outbox", n)).isZero();
    assertThat(count("pas.policy_audit", n)).isZero();
  }

  @Test
  void failedCoverageUpdateRollsBackTheWholeRenewal() {
    String n = "POLLC0000004";
    policy(n, "EX", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));
    coverage(n, 1, "AC", LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));
    jdbc.execute(
        "CREATE FUNCTION pas.fail_cov() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'simulated DB2 -911'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_cov BEFORE UPDATE ON pas.coverages FOR EACH ROW"
            + " WHEN (NEW.policy_number = 'POLLC0000004') EXECUTE FUNCTION pas.fail_cov()");
    try {
      assertThat(renew(n, writer()).getStatusCode().value()).isEqualTo(500);
    } finally {
      jdbc.execute("DROP TRIGGER fail_cov ON pas.coverages");
      jdbc.execute("DROP FUNCTION pas.fail_cov()");
    }
    Map<String, Object> p = policyRow(n);
    assertThat(p.get("policy_status")).isEqualTo("EX");
    assertThat((BigDecimal) p.get("total_premium")).isEqualByComparingTo("1000.00");
    assertThat(((Number) p.get("version")).longValue()).isZero();
    assertThat(count("pas.outbox", n)).isZero();
    assertThat(count("pas.policy_audit", n)).isZero();
  }

  // ------------------------------------------------------------ endorsement

  @Test
  void endorsementWritesRowAdjustsPolicyAuditsAndPublishes() throws Exception {
    String n = "POLLC0000010";
    LocalDate eff = today().minusDays(100);
    policy(n, "AC", eff, eff.plusYears(1));
    jdbc.update(
        "INSERT INTO pas.endorsements (policy_number, endorsement_seq, endorsement_type,"
            + " effective_date, premium_adjustment, processed_date, processed_by)"
            + " VALUES (?, 3, 'ACH', ?, 0, LOCALTIMESTAMP, 'POLEND')",
        n,
        eff);

    ResponseEntity<String> res = endorse(n, writer(), "LCH");
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode e = mapper.readTree(res.getBody());
    int term = (int) java.time.temporal.ChronoUnit.DAYS.between(eff, eff.plusYears(1));
    int remaining = term - 100;
    assertThat(e.get("endorsementSeq").asInt()).isEqualTo(4);
    assertThat(e.get("daysInTerm").asInt()).isEqualTo(term);
    assertThat(e.get("daysRemaining").asInt()).isEqualTo(remaining);
    assertThat(e.get("proRataFactor").decimalValue())
        .isEqualByComparingTo(
            BigDecimal.valueOf(remaining)
                .divide(BigDecimal.valueOf(term), 6, java.math.RoundingMode.DOWN));
    assertThat(e.get("premiumAdjustment").decimalValue()).isEqualByComparingTo("0.00");
    assertThat(e.get("totalPremium").decimalValue()).isEqualByComparingTo("1000.00");
    assertThat(e.get("effectiveDate").asText()).isEqualTo(today().toString());
    assertThat(e.get("policyVersion").asLong()).isEqualTo(1);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT endorsement_type, effective_date, premium_adjustment, processed_by"
                + " FROM pas.endorsements WHERE policy_number = ? AND endorsement_seq = 4",
            n);
    assertThat(row.get("endorsement_type")).isEqualTo("LCH");
    assertThat(((String) row.get("processed_by")).strip()).isEqualTo("POLEND");
    Map<String, Object> p = policyRow(n);
    assertThat(((String) p.get("updated_by")).strip()).isEqualTo("POLEND");
    assertThat(((Number) p.get("version")).longValue()).isEqualTo(1);

    JsonNode event = lastEvent(n);
    assertThat(event.get("eventType").asText()).isEqualTo("PolicyEndorsed");
    assertThat(event.at("/data/endorsementSeq").asInt()).isEqualTo(4);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, program, details->'change'->>'endorsementSeq' AS seq"
                + " FROM pas.policy_audit WHERE policy_number = ?",
            n);
    assertThat(audit.get("action")).isEqualTo("ENDORSEMENT");
    assertThat(audit.get("program")).isEqualTo("POLEND");
    assertThat(audit.get("seq")).isEqualTo("4");
  }

  @Test
  void zeroLengthTermUsesFactorOne() throws Exception {
    String n = "POLLC0000011";
    policy(n, "AC", today(), today());
    ResponseEntity<String> res = endorse(n, writer(), "CAD");
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    JsonNode e = mapper.readTree(res.getBody());
    assertThat(e.get("daysInTerm").asInt()).isZero();
    assertThat(e.get("daysRemaining").asInt()).isZero();
    assertThat(e.get("proRataFactor").decimalValue()).isEqualByComparingTo("1.000000");
    assertThat(e.get("endorsementSeq").asInt()).isEqualTo(1);
  }

  @Test
  void endorsementValidationFollowsPolend() throws Exception {
    String active = "POLLC0000012";
    String pending = "POLLC0000013";
    policy(active, "AC", today().minusDays(10), today().plusDays(355));
    policy(pending, "PN", today().minusDays(10), today().plusDays(355));

    ResponseEntity<String> blank = endorse(active, writer(), " ");
    assertThat(blank.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(blank.getBody()).get("message").asText())
        .isEqualTo("ENDORSEMENT TYPE IS REQUIRED");
    assertThat(endorse(active, writer(), null).getStatusCode().value()).isEqualTo(422);
    assertThat(endorse(active, writer(), "XYZ").getStatusCode().value()).isEqualTo(400);

    ResponseEntity<String> inactive = endorse(pending, writer(), "CAD");
    assertThat(inactive.getStatusCode().value()).isEqualTo(422);
    assertThat(mapper.readTree(inactive.getBody()).get("message").asText())
        .isEqualTo("ONLY ACTIVE POLICIES CAN BE ENDORSED");
    // both checks fail: the last one (type) wins, as in POLEND
    assertThat(mapper.readTree(endorse(pending, writer(), "").getBody()).get("message").asText())
        .isEqualTo("ENDORSEMENT TYPE IS REQUIRED");
    assertThat(count("pas.endorsements", active)).isZero();
    assertThat(count("pas.outbox", active)).isZero();
  }

  @Test
  void failedEndorsementInsertChangesNothing() {
    String n = "POLLC0000014";
    policy(n, "AC", today().minusDays(10), today().plusDays(355));
    jdbc.execute(
        "CREATE FUNCTION pas.fail_end() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'simulated DB2 -803'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_end BEFORE INSERT ON pas.endorsements FOR EACH ROW"
            + " WHEN (NEW.policy_number = 'POLLC0000014') EXECUTE FUNCTION pas.fail_end()");
    try {
      assertThat(endorse(n, writer(), "CAD").getStatusCode().value()).isEqualTo(500);
    } finally {
      jdbc.execute("DROP TRIGGER fail_end ON pas.endorsements");
      jdbc.execute("DROP FUNCTION pas.fail_end()");
    }
    Map<String, Object> p = policyRow(n);
    assertThat(((Number) p.get("version")).longValue()).isZero();
    assertThat(((String) p.get("updated_by")).strip()).isNotEqualTo("POLEND");
    assertThat(count("pas.outbox", n)).isZero();
    assertThat(count("pas.policy_audit", n)).isZero();
  }

  @Test
  void concurrentEndorsementsOnOnePolicyAreSerialisedWithoutLostUpdates() throws Exception {
    String n = "POLLC0000015";
    policy(n, "AC", today().minusDays(10), today().plusDays(355));
    int threads = 8;
    String bearer = writer();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ResponseEntity<String>>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        Callable<ResponseEntity<String>> call =
            () -> {
              start.await();
              return endorse(n, bearer, "CAD");
            };
        results.add(pool.submit(call));
      }
      start.countDown();
      List<Integer> seqs = new ArrayList<>();
      List<Long> versions = new ArrayList<>();
      for (Future<ResponseEntity<String>> f : results) {
        ResponseEntity<String> res = f.get();
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode e = mapper.readTree(res.getBody());
        seqs.add(e.get("endorsementSeq").asInt());
        versions.add(e.get("policyVersion").asLong());
      }
      assertThat(seqs).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
      assertThat(versions).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    } finally {
      pool.shutdownNow();
    }
    assertThat(((Number) policyRow(n).get("version")).longValue()).isEqualTo(threads);
    assertThat(count("pas.endorsements", n)).isEqualTo(threads);
    assertThat(count("pas.policy_audit", n)).isEqualTo(threads);
    assertThat(
            jdbc.queryForList(
                "SELECT (payload::jsonb->>'policyVersion')::bigint FROM pas.outbox"
                    + " WHERE message_key = ? ORDER BY outbox_id",
                Long.class,
                n))
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
  }

  // --------------------------------------------------------------- security

  @ParameterizedTest
  @ValueSource(strings = {"renewals", "endorsements"})
  void securityNoTokenWrongScopeAndCrossTenant(String endpoint) {
    String n = "POLLC00020" + endpoint.substring(0, 2).toUpperCase();
    policy(n, "AC", today().minusDays(10), today().plusDays(355));
    String path = "/api/v1/policies/" + n + "/" + endpoint;
    String body = "{\"endorsementType\":\"CAD\"}";

    assertThat(send(rest, HttpMethod.POST, path, null, body, Map.of()).getStatusCode().value())
        .isEqualTo(401);
    String readOnly = TestJwtIssuer.bearer().scopes(PasScopes.POLICY_READ).agent("AG0001").sign();
    assertThat(send(rest, HttpMethod.POST, path, readOnly, body, Map.of()).getStatusCode().value())
        .isEqualTo(403);
    String otherAgent =
        TestJwtIssuer.bearer().scopes(PasScopes.POLICY_WRITE).agent("AG0009").sign();
    assertThat(
            send(rest, HttpMethod.POST, path, otherAgent, body, Map.of()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(
            send(
                    rest,
                    HttpMethod.POST,
                    "/api/v1/policies/POLNOTEXIST1/" + endpoint,
                    writer(),
                    body,
                    Map.of())
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(((Number) policyRow(n).get("version")).longValue()).isZero();
  }
}
