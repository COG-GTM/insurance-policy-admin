package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import com.acme.insurance.pas.policy.dev.FacadeSeedLoader;
import com.acme.insurance.pas.policy.lifecycle.PolicyAuditRepository;
import com.acme.insurance.pas.policy.renewal.PolicyRenewalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AB-526 renewal (POLRNW) and endorsement (POLEND) on the NEW route. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "pas.renewal.window-days-after-expiry=3650")
@ActiveProfiles("it")
class RenewalEndorsementIT {
  static final String SEED_POLICY = "POL-00000001";
  static final String SCOPES = "policy:renew policy:endorse policy:read";
  static final Map<String, Integer> OPEN_CLAIMS = new ConcurrentHashMap<>();
  static final String CLAIMS_DOWN = "claims-down";
  private static final AtomicLong NUMBERS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private FacadeSeedLoader seedLoader;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private PolicyRenewalRepository renewalRepository;
  @MockitoSpyBean private PolicyAuditRepository auditRepository;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @TestConfiguration
  static class ClaimsDouble {
    @Bean
    ClaimsHistoryPort claimsHistoryPort() {
      return new ClaimsHistoryPort() {
        @Override
        public int countOpenClaims(String policyNumber) {
          if (OPEN_CLAIMS.getOrDefault(policyNumber.trim(), 0) < 0) {
            throw new IllegalStateException(CLAIMS_DOWN);
          }
          return OPEN_CLAIMS.getOrDefault(policyNumber.trim(), 0);
        }

        @Override
        public ClaimsSummary summarizeSince(String policyNumber, LocalDate since) {
          return new ClaimsSummary(countOpenClaims(policyNumber), BigDecimal.ZERO);
        }
      };
    }
  }

  @BeforeEach
  void seed() {
    seedLoader.load();
  }

  @Test
  void feb29TermRenewsToFeb28WithTruncatedUpliftAndRedatedCoverages() throws Exception {
    String policy =
        copyPolicy(LocalDate.of(2023, 3, 1), LocalDate.of(2024, 2, 29), "AC", "1234.57", 1);
    ResponseEntity<String> response = renew(policy, "2024-02-29", wholeBook());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.get("effectiveDate").asText()).isEqualTo("2024-02-29");
    assertThat(body.get("expiryDate").asText()).isEqualTo("2025-02-28");
    assertThat(body.get("totalPremium").decimalValue()).isEqualByComparingTo("1296.29");
    assertThat(body.get("renewalCount").asInt()).isEqualTo(2);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT expiry_date, total_premium, renewal_count, uw_status, updated_by FROM pas.policies WHERE policy_number = ?",
            policy);
    assertThat(row.get("expiry_date").toString()).isEqualTo("2025-02-28");
    assertThat((BigDecimal) row.get("total_premium")).isEqualByComparingTo("1296.29");
    assertThat(row.get("uw_status").toString().trim()).isEqualTo("PN");
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT expiry_date::text FROM pas.coverages WHERE policy_number = ? AND status = 'AC'",
                String.class,
                policy))
        .containsExactly("2025-02-28");
    assertThat(
            jdbc.queryForObject(
                "SELECT actor FROM pas.policy_audit WHERE policy_number = ? AND action = 'RENEWAL'",
                String.class,
                policy))
        .isEqualTo("renewal-tester");
  }

  @Test
  void secondRenewalOfTheSameTermIsConflict() throws Exception {
    String policy = copyPolicy(today().minusDays(335), today().plusDays(30), "AC", "1000.00", 0);
    String term = today().plusDays(30).toString();
    assertThat(renew(policy, term, wholeBook()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<String> again = renew(policy, term, wholeBook());
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(again.getBody()).contains("TERM_ALREADY_RENEWED");
    assertThat(count("pas.policy_renewals", policy)).isEqualTo(1);
  }

  @Test
  void openClaimsAboveThresholdBlockAndClaimsOutageFailsClosed() throws Exception {
    String blocked = copyPolicy(today().minusDays(335), today().plusDays(30), "AC", "1000.00", 0);
    OPEN_CLAIMS.put(blocked, 4);
    ResponseEntity<String> response = renew(blocked, today().plusDays(30).toString(), wholeBook());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).contains("TOO_MANY_OPEN_CLAIMS");

    String down = copyPolicy(today().minusDays(335), today().plusDays(30), "AC", "1000.00", 0);
    OPEN_CLAIMS.put(down, -1);
    assertThat(renew(down, today().plusDays(30).toString(), wholeBook()).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(count("pas.policy_renewals", blocked) + count("pas.policy_renewals", down)).isZero();
  }

  @Test
  void renewalCountAtLegacyMaximumIsRejectedNotWrapped() throws Exception {
    String policy = copyPolicy(today().minusDays(335), today().plusDays(30), "AC", "1000.00", 999);
    ResponseEntity<String> response = renew(policy, today().plusDays(30).toString(), wholeBook());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).contains("RENEWAL_COUNT_LIMIT");
  }

  @Test
  void failedRenewalWriteRollsBackPolicyAndCoverages() throws Exception {
    String policy = copyPolicy(today().minusDays(335), today().plusDays(30), "AC", "1000.00", 0);
    doThrow(new DataIntegrityViolationException("simulated"))
        .when(renewalRepository)
        .insert(argThat(record -> record != null && policy.equals(record.policyNumber().trim())));
    ResponseEntity<String> response = renew(policy, today().plusDays(30).toString(), wholeBook());
    assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT expiry_date, total_premium, renewal_count FROM pas.policies WHERE policy_number = ?",
            policy);
    assertThat(row.get("expiry_date").toString()).isEqualTo(today().plusDays(30).toString());
    assertThat((BigDecimal) row.get("total_premium")).isEqualByComparingTo("1000.00");
    assertThat(((Number) row.get("renewal_count")).intValue()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.coverages WHERE policy_number = ? AND expiry_date <> ?",
                Integer.class,
                policy,
                today().plusDays(30)))
        .isZero();
    assertThat(count("pas.policy_audit", policy)).isZero();
  }

  @Test
  void endorsementOnFirstAndLastDayOfTermAndOutsideIt() throws Exception {
    LocalDate effective = today().minusDays(100);
    LocalDate expiry = effective.plusYears(1);
    String policy = copyPolicy(effective, expiry, "AC", "1200.00", 0);
    ResponseEntity<String> first = endorse(policy, cancelLike("LCH", effective), wholeBook());
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode firstBody = objectMapper.readTree(first.getBody());
    assertThat(firstBody.get("proRataFactor").decimalValue()).isEqualByComparingTo("1.000000");
    assertThat(firstBody.get("premiumAdjustment").decimalValue()).isEqualByComparingTo("0.00");
    assertThat(firstBody.get("endorsementSeq").asInt()).isEqualTo(1);

    ResponseEntity<String> last = endorse(policy, cancelLike("LCH", expiry), wholeBook());
    assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(objectMapper.readTree(last.getBody()).get("proRataFactor").decimalValue())
        .isEqualByComparingTo("0.000000");

    ResponseEntity<String> outside =
        endorse(policy, cancelLike("LCH", expiry.plusDays(1)), wholeBook());
    assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(outside.getBody()).contains("OUTSIDE_TERM");
    assertThat(
            jdbc.queryForObject(
                "SELECT coverage_limit FROM pas.coverages WHERE policy_number = ? AND sequence_num = 1",
                BigDecimal.class,
                policy))
        .isEqualByComparingTo("250000.00");
  }

  @Test
  void zeroLengthTermAndInvalidTypeAreUnprocessable() throws Exception {
    LocalDate day = today();
    String policy = copyPolicy(day, day, "AC", "1200.00", 0);
    ResponseEntity<String> zero = endorse(policy, cancelLike("CAN", day), wholeBook());
    assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(zero.getBody()).contains("ZERO_LENGTH_TERM");

    String other = copyPolicy(day.minusDays(10), day.plusDays(300), "AC", "1200.00", 0);
    ResponseEntity<String> type = endorse(other, cancelLike("XYZ", day), wholeBook());
    assertThat(type.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(type.getBody()).contains("ENDORSEMENT_TYPE_INVALID");
    assertThat(count("pas.endorsements", policy) + count("pas.endorsements", other)).isZero();
  }

  @Test
  void failedAuditWriteRollsBackEndorsementEffectAndPolicy() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    doThrow(new DataIntegrityViolationException("simulated"))
        .when(auditRepository)
        .record(
            argThat(number -> number != null && policy.equals(number.trim())),
            eq(PolicyAuditRepository.ENDORSEMENT),
            anyString(),
            anyString(),
            any(),
            any(),
            any());
    assertThat(
            endorse(policy, cancelLike("CAN", today()), wholeBook())
                .getStatusCode()
                .is5xxServerError())
        .isTrue();
    assertThat(count("pas.endorsements", policy)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT policy_status FROM pas.policies WHERE policy_number = ?",
                String.class,
                policy))
        .isEqualTo("AC");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.coverages WHERE policy_number = ? AND status = 'AC'",
                Integer.class,
                policy))
        .isEqualTo(2);
  }

  @Test
  void unknownCoverageAndRemovalBeforeCoverageStartAreRejected() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    ResponseEntity<String> unknown =
        endorse(
            policy,
            Map.of("type", "CRM", "effectiveDate", today().toString(), "coverageSeq", 99),
            wholeBook());
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(unknown.getBody()).contains("COVERAGE_NOT_FOUND");

    Map<String, Object> addition =
        Map.of(
            "type",
            "CAD",
            "effectiveDate",
            today().plusDays(10).toString(),
            "coverage",
            Map.of("coverageType", "DWEL", "coverageLimit", 100000, "premium", 50));
    assertThat(endorse(policy, addition, wholeBook()).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    ResponseEntity<String> backdated =
        endorse(
            policy,
            Map.of("type", "CRM", "effectiveDate", today().toString(), "coverageSeq", 3),
            wholeBook());
    assertThat(backdated.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM pas.coverages WHERE policy_number = ? AND sequence_num = 3",
                String.class,
                policy))
        .isEqualTo("AC");
    assertThat(count("pas.endorsements", policy)).isEqualTo(1);
  }

  @Test
  void addressChangeIsRefusedWhenTheHolderHasPoliciesOutsideTheCallersBook() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "900.00", 0, "AG9999");
    Map<String, Object> request =
        Map.of(
            "type",
            "ACH",
            "effectiveDate",
            today().toString(),
            "address",
            Map.of(
                "addrLine1",
                "1 New Street",
                "city",
                "Springfield",
                "stateCode",
                "il",
                "zipCode",
                "62701"));
    ResponseEntity<String> agent = endorse(policy, request, Map.of("agent_id", "AG1001"));
    assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(agent.getBody()).contains("ADDRESS_CHANGE_NOT_PERMITTED");

    assertThat(endorse(policy, request, wholeBook()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> holder =
        jdbc.queryForMap(
            "SELECT h.city, h.state_code, h.last_updated > now() - interval '1 minute' AS fresh FROM pas.policy_holders h JOIN pas.policies p ON p.policyholder_id = h.cust_id WHERE p.policy_number = ?",
            policy);
    assertThat(holder.get("city").toString().trim()).isEqualTo("Springfield");
    assertThat(holder.get("state_code").toString()).isEqualTo("IL");
    assertThat(holder.get("fresh")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void cancellationEndsActiveCoveragesAndPolicy() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    ResponseEntity<String> response = endorse(policy, cancelLike("CAN", today()), wholeBook());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            jdbc.queryForObject(
                "SELECT policy_status FROM pas.policies WHERE policy_number = ?",
                String.class,
                policy))
        .isEqualTo("CN");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.coverages WHERE policy_number = ? AND status = 'AC'",
                Integer.class,
                policy))
        .isZero();
  }

  @Test
  void concurrentEndorsementsOnOnePolicyAreSerialised() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    String token = PolicyJwtTestSupport.mint(SCOPES, "concurrent", wholeBook());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<HttpStatus>> calls =
          List.of(
              () -> {
                start.await();
                return (HttpStatus) endorseWith(policy, limitChange(1, "300000.00"), token);
              },
              () -> {
                start.await();
                return (HttpStatus) endorseWith(policy, limitChange(2, "75000.00"), token);
              },
              () -> {
                start.await();
                return (HttpStatus) endorseWith(policy, limitChange(1, "310000.00"), token);
              },
              () -> {
                start.await();
                return (HttpStatus) endorseWith(policy, limitChange(2, "80000.00"), token);
              });
      List<Future<HttpStatus>> futures = calls.stream().map(pool::submit).toList();
      start.countDown();
      for (Future<HttpStatus> future : futures) {
        assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CREATED);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForList(
                "SELECT endorsement_seq FROM pas.endorsements WHERE policy_number = ? ORDER BY 1",
                Integer.class,
                policy))
        .containsExactly(1, 2, 3, 4);
    assertThat(count("pas.policy_audit", policy)).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_premium FROM pas.policies WHERE policy_number = ?",
                BigDecimal.class,
                policy))
        .isEqualByComparingTo("1200.00");
  }

  @Test
  void scopesAndObjectLevelAuthorisation() throws Exception {
    String policy = copyPolicy(today().minusDays(10), today().plusDays(300), "AC", "1200.00", 0);
    Map<String, Object> body = cancelLike("LCH", today());
    assertThat(exchange(policy, "endorsements", body, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            exchange(
                    policy,
                    "endorsements",
                    body,
                    PolicyJwtTestSupport.mint("policy:read policy:renew", "x", wholeBook()))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            exchange(
                    policy,
                    "renewals",
                    Map.of("termExpiryDate", today().plusDays(300).toString()),
                    PolicyJwtTestSupport.mint("policy:read policy:endorse", "x", wholeBook()))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    String outsider = PolicyJwtTestSupport.mint(SCOPES, "outsider", Map.of("agent_id", "AG9999"));
    assertThat(exchange(policy, "endorsements", body, outsider).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    policy,
                    "renewals",
                    Map.of("termExpiryDate", today().plusDays(300).toString()),
                    outsider)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            exchange(
                    "POL-99999999",
                    "endorsements",
                    body,
                    PolicyJwtTestSupport.mint(SCOPES, "x", wholeBook()))
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(count("pas.endorsements", policy)).isZero();
  }

  private String copyPolicy(
      LocalDate effective, LocalDate expiry, String status, String premium, int renewalCount) {
    return copyPolicy(effective, expiry, status, premium, renewalCount, null);
  }

  private String copyPolicy(
      LocalDate effective,
      LocalDate expiry,
      String status,
      String premium,
      int renewalCount,
      String agentCode) {
    String number = String.format("T%011d", NUMBERS.incrementAndGet() % 100_000_000_000L);
    jdbc.update(
        """
        INSERT INTO pas.policies (
            policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id,
            agent_code, branch_code, total_premium, deductible, coverage_limit, inception_date,
            renewal_count, uw_status, risk_score, web_indicator, api_flag, last_updated, updated_by)
        SELECT ?, policy_type, ?, ?, ?, policyholder_id, COALESCE(?, agent_code), branch_code, ?,
               deductible,
               coverage_limit, ?, ?, 'AP', risk_score, web_indicator, api_flag, now(), 'IT'
        FROM pas.policies WHERE policy_number = ?
        """,
        number,
        status,
        effective,
        expiry,
        agentCode,
        new BigDecimal(premium),
        effective,
        renewalCount,
        SEED_POLICY);
    jdbc.update(
        "INSERT INTO pas.coverages (policy_number, sequence_num, coverage_type, description, coverage_limit, deductible, premium, effective_date, expiry_date, status) VALUES (?, 1, 'LIAB', 'Liability', 250000.00, 500.00, 600.00, ?, ?, 'AC'), (?, 2, 'PROP', 'Property', 50000.00, 500.00, 600.00, ?, ?, 'AC')",
        number,
        effective,
        expiry,
        number,
        effective,
        expiry);
    return number;
  }

  private static Map<String, Object> cancelLike(String type, LocalDate date) {
    if ("LCH".equals(type)) {
      return Map.of(
          "type", type, "effectiveDate", date.toString(), "coverageSeq", 2, "newLimit", 60000);
    }
    return Map.of("type", type, "effectiveDate", date.toString());
  }

  private static Map<String, Object> limitChange(int sequence, String limit) {
    return Map.of(
        "type",
        "LCH",
        "effectiveDate",
        today().toString(),
        "coverageSeq",
        sequence,
        "newLimit",
        new BigDecimal(limit));
  }

  private int count(String table, String policy) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE policy_number = ?", Integer.class, policy);
  }

  private ResponseEntity<String> renew(String policy, String term, Map<String, Object> claims)
      throws Exception {
    return exchange(
        policy,
        "renewals",
        Map.of("termExpiryDate", term),
        PolicyJwtTestSupport.mint(SCOPES, "renewal-tester", claims));
  }

  private ResponseEntity<String> endorse(
      String policy, Map<String, Object> body, Map<String, Object> claims) throws Exception {
    return exchange(
        policy,
        "endorsements",
        body,
        PolicyJwtTestSupport.mint(SCOPES, "endorsement-tester", claims));
  }

  private Object endorseWith(String policy, Map<String, Object> body, String token) {
    return exchange(policy, "endorsements", body, token).getStatusCode();
  }

  private ResponseEntity<String> exchange(
      String policy, String resource, Map<String, Object> body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return restTemplate.exchange(
        "/api/v1/policies/" + policy + "/" + resource,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private static Map<String, Object> wholeBook() {
    return Map.of("book", "ALL");
  }

  private static LocalDate today() {
    return LocalDate.now(java.time.ZoneOffset.UTC);
  }
}
