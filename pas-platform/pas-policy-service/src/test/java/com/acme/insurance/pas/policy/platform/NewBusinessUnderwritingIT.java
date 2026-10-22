package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.policy.events.CorrelationIds;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessRequest;
import com.acme.insurance.pas.policy.newbusiness.NewBusinessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(NewBusinessUnderwritingIT.TestClockConfiguration.class)
class NewBusinessUnderwritingIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NewBusinessService newBusiness;
  @Autowired private MutableClock testClock;
  @Autowired private PlatformTransactionManager transactionManager;
  @LocalServerPort private int port;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @Test
  void acceptsMigratedPolicyNumberShapeAndNormalizesBeforeLookup() throws Exception {
    String decideJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.UNDERWRITING_DECIDE, "migrated-number-underwriter", Map.of("book", "ALL"));

    ResponseEntity<String> response =
        post("/api/v1/policies/pol-00000001/underwriting-decisions", "", decideJwt, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void replaysAnIdempotentCreateAfterItsEffectiveDateHasBecomePast() throws Exception {
    Instant initial = testClock.instant();
    LocalDate effectiveDate = LocalDate.now(testClock).plusDays(1);
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String jwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "clocked-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    String body =
        """
        {"policyType":"AUT","policyholderId":"%s","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000,
         "deductible":500,"totalPremium":1200}
        """
            .formatted(custId, effectiveDate);

    ResponseEntity<String> created = post("/api/v1/policies", body, jwt, "past-date-replay");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String policyNumber = MAPPER.readTree(created.getBody()).path("policyNumber").asText();

    testClock.setInstant(initial.plusSeconds(172_800));
    ResponseEntity<String> replayed;
    try {
      replayed = post("/api/v1/policies", body, jwt, "past-date-replay");
    } finally {
      testClock.setInstant(Instant.now());
    }

    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed.getBody()).isEqualTo(created.getBody());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.policies WHERE policy_number = ?",
                Integer.class,
                policyNumber))
        .isEqualTo(1);
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

    String suppliedCorrelationId = "create-correlation-123";
    ResponseEntity<String> created =
        post("/api/v1/policies", request, agentJwt, "create-once", suppliedCorrelationId);
    ResponseEntity<String> replay = post("/api/v1/policies", request, agentJwt, "create-once");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getHeaders().getFirst(CorrelationIds.HEADER_NAME))
        .isEqualTo(suppliedCorrelationId);
    assertThat(created.getHeaders().getLocation()).isEqualTo(replay.getHeaders().getLocation());
    assertThat(replay.getBody()).isEqualTo(created.getBody());
    UUID.fromString(replay.getHeaders().getFirst(CorrelationIds.HEADER_NAME));
    assertThat(
            post("/api/v1/policies", request.replace("1200.00", "1300.00"), agentJwt, "create-once")
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
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
    assertThat(
            jdbc.queryForList(
                "SELECT payload ->> 'correlationId' FROM pas.outbox WHERE aggregate_id = ?",
                String.class,
                policyNumber))
        .containsExactlyInAnyOrder(suppliedCorrelationId, suppliedCorrelationId);

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
    BigDecimal originalLossRatio = second.path("lossRatio").decimalValue();
    assertThat(
            jdbc.queryForObject(
                "SELECT loss_ratio FROM pas.underwriting_decisions WHERE decision_id = ?",
                BigDecimal.class,
                second.path("decisionId").asLong()))
        .isEqualByComparingTo(originalLossRatio);

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
    assertThat(
            post(
                    "/api/v1/policies/"
                        + policyNumber
                        + "/underwriting-decisions/"
                        + first.path("decisionId").asLong()
                        + "/override",
                    "{\"decisionCode\":\"AP\",\"reason\":\"Not latest\"}",
                    overrideJwt,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            post(
                    "/api/v1/policies/" + policyNumber + "/underwriting-decisions/999999/override",
                    "{\"decisionCode\":\"AP\",\"reason\":\"Missing\"}",
                    overrideJwt,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            post(
                    "/api/v1/policies/"
                        + policyNumber
                        + "/underwriting-decisions/"
                        + second.path("decisionId").asLong()
                        + "/override",
                    "{\"decisionCode\":\"AP\",\"reason\":\"\"}",
                    overrideJwt,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(
            jdbc.queryForObject(
                "SELECT overrides_decision_id FROM pas.underwriting_decisions WHERE decision_id = ?",
                Long.class,
                MAPPER.readTree(override.getBody()).path("decisionId").asLong()))
        .isEqualTo(second.path("decisionId").asLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT loss_ratio FROM pas.underwriting_decisions WHERE decision_id = ?",
                BigDecimal.class,
                MAPPER.readTree(override.getBody()).path("decisionId").asLong()))
        .isEqualByComparingTo(originalLossRatio);
    assertThat(
            jdbc.queryForObject(
                "SELECT row_version FROM pas.policies WHERE policy_number = ?",
                Long.class,
                policyNumber))
        .isEqualTo(4L);
    String readJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_READ,
            "policy-reader",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    ResponseEntity<String> listed =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v1/policies/"
                + policyNumber
                + "/underwriting-decisions",
            HttpMethod.GET,
            new HttpEntity<>(headers(readJwt)),
            String.class);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(MAPPER.readTree(listed.getBody())).hasSize(3);
    String fallbackReason =
        jdbc.queryForObject(
                "SELECT decision_reason FROM pas.underwriting_decisions WHERE decision_id = ?",
                String.class,
                first.path("decisionId").asLong())
            .trim();
    jdbc.update(
        "UPDATE pas.underwriting_decisions SET decision_reasons = NULL WHERE decision_id = ?",
        first.path("decisionId").asLong());
    ResponseEntity<String> listedAfterLegacyReason =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v1/policies/"
                + policyNumber
                + "/underwriting-decisions",
            HttpMethod.GET,
            new HttpEntity<>(headers(readJwt)),
            String.class);
    boolean fallbackReasonFound = false;
    for (JsonNode audit : MAPPER.readTree(listedAfterLegacyReason.getBody())) {
      if (audit.path("decisionId").asLong() == first.path("decisionId").asLong()) {
        assertThat(audit.path("reasons").get(0).asText()).isEqualTo(fallbackReason);
        fallbackReasonFound = true;
      }
    }
    assertThat(fallbackReasonFound).isTrue();
    String otherBranchReadJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_READ,
            "other-reader",
            Map.of("book", "AGENT", "agent_id", "AGT999", "branch_codes", List.of("X999")));
    ResponseEntity<String> hidden =
        restTemplate.exchange(
            "http://localhost:"
                + port
                + "/api/v1/policies/"
                + policyNumber
                + "/underwriting-decisions",
            HttpMethod.GET,
            new HttpEntity<>(headers(otherBranchReadJwt)),
            String.class);
    assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
    String branchOnlyJwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "branch-only-creator",
            Map.of("book", "AGENT", "branch_codes", List.of("B001")));
    String foreignAgentBranchRequest =
        unentitledRequest.replace("\"branchCode\":\"X001\"", "\"branchCode\":\"B001\"");
    assertThat(
            post("/api/v1/policies", foreignAgentBranchRequest, branchOnlyJwt, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.policies WHERE policyholder_id = ?",
                Integer.class,
                custId))
        .isZero();
    String missingHolderRequest =
        """
        {"policyType":"AUT","policyholderId":"C999999999","effectiveDate":"%s",
         "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000}
        """
            .formatted(LocalDate.now());
    assertThat(post("/api/v1/policies", missingHolderRequest, writeJwt, null).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    ResponseEntity<String> hiddenMissingHolder =
        post(
            "/api/v1/policies",
            missingHolderRequest.replace("AGT001", "OTHER1").replace("B001", "X001"),
            writeJwt,
            null);
    assertThat(hiddenMissingHolder.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(hiddenMissingHolder.getBody()).doesNotContain("POLICYHOLDER NOT FOUND");
    String todayRequest =
        unentitledRequest
            .replace("OTHER1", "AGT001")
            .replace("X001", "B001")
            .replace(LocalDate.now().plusDays(1).toString(), LocalDate.now().toString());
    String yesterdayRequest =
        todayRequest.replace(LocalDate.now().toString(), LocalDate.now().minusDays(1).toString());
    assertThat(post("/api/v1/policies", yesterdayRequest, writeJwt, null).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(post("/api/v1/policies", todayRequest, writeJwt, null).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void rejectsInvalidPolicyNumbersBeforeUnderwritingReadsOrWrites() throws Exception {
    String jwt = PolicyJwtTestSupport.mint(PasScopes.UNDERWRITING_DECIDE, "underwriter", Map.of());
    int decisionsBefore =
        jdbc.queryForObject("SELECT count(*) FROM pas.underwriting_decisions", Integer.class);
    int outboxBefore = jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class);

    assertThat(
            post("/api/v1/policies/not_a_policy/underwriting-decisions", "", jwt, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM pas.underwriting_decisions", Integer.class))
        .isEqualTo(decisionsBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class))
        .isEqualTo(outboxBefore);
  }

  @Test
  void rollsBackPolicyIdempotencyAndOutboxWritesTogether() {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    int policyCountBefore =
        jdbc.queryForObject(
            "SELECT count(*) FROM pas.policies WHERE policyholder_id = ?", Integer.class, custId);
    int coverageCountBefore =
        jdbc.queryForObject("SELECT count(*) FROM pas.coverages", Integer.class);
    int outboxCountBefore = jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class);
    NewBusinessRequest request =
        new NewBusinessRequest(
            "AUT",
            custId,
            LocalDate.now(),
            "AGT001",
            "B001",
            new java.math.BigDecimal("100000.00"),
            new java.math.BigDecimal("500.00"),
            new java.math.BigDecimal("1200.00"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          newBusiness.create(
                              request,
                              "rollback-key",
                              "rollback-agent",
                              new CallerEntitlements(true, null, List.of()),
                              false,
                              "rollback-correlation");
                          throw new IllegalStateException("force transaction rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("force transaction rollback");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.policies WHERE policyholder_id = ?",
                Integer.class,
                custId))
        .isEqualTo(policyCountBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.coverages", Integer.class))
        .isEqualTo(coverageCountBefore);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pas.outbox", Integer.class))
        .isEqualTo(outboxCountBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.idempotency_keys WHERE caller_sub = ? AND idempotency_key = ?",
                Integer.class,
                "rollback-agent",
                "rollback-key"))
        .isZero();
  }

  @Test
  void createsConfiguredCoverageDefaultsForEachPolicyType() throws Exception {
    String custId = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    jdbc.update("INSERT INTO pas.policy_holders (cust_id, cust_type) VALUES (?, 'I')", custId);
    String jwt =
        PolicyJwtTestSupport.mint(
            PasScopes.POLICY_WRITE,
            "policy-creator",
            Map.of("book", "AGENT", "agent_id", "AGT001", "branch_codes", List.of("B001")));
    Map<String, Integer> expectedCoverageCounts = Map.of("AUT", 2, "HOM", 2, "COM", 3, "LIF", 0);
    for (Map.Entry<String, Integer> entry : expectedCoverageCounts.entrySet()) {
      String body =
          """
          {"policyType":"%s","policyholderId":"%s","effectiveDate":"%s",
           "agentCode":"AGT001","branchCode":"B001","coverageLimit":100000}
          """
              .formatted(entry.getKey(), custId, LocalDate.now());
      ResponseEntity<String> created =
          post("/api/v1/policies", body, jwt, "coverage-" + entry.getKey() + UUID.randomUUID());
      assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      String policyNumber = MAPPER.readTree(created.getBody()).path("policyNumber").asText();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM pas.coverages WHERE policy_number = ?",
                  Integer.class,
                  policyNumber))
          .isEqualTo(entry.getValue());
    }
  }

  @Test
  void requiresAuthenticationAndEndpointSpecificScopes() throws Exception {
    assertThat(post("/api/v1/policies", "{}", null, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            post("/api/v1/policies/POL000000001/underwriting-decisions", "", null, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            post(
                    "/api/v1/policies/POL000000001/underwriting-decisions/1/override",
                    "{}",
                    null,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/policies/POL000000001/underwriting-decisions", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    String readJwt = PolicyJwtTestSupport.mint(PasScopes.POLICY_READ, "reader", Map.of());
    String writeJwt = PolicyJwtTestSupport.mint(PasScopes.POLICY_WRITE, "writer", Map.of());
    String decideJwt =
        PolicyJwtTestSupport.mint(PasScopes.UNDERWRITING_DECIDE, "decider", Map.of());
    assertThat(post("/api/v1/policies", "{}", readJwt, null).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            post("/api/v1/policies/POL000000001/underwriting-decisions", "", writeJwt, null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            post(
                    "/api/v1/policies/POL000000001/underwriting-decisions/1/override",
                    "{}",
                    decideJwt,
                    null)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            get("/api/v1/policies/POL000000001/underwriting-decisions", writeJwt).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  private ResponseEntity<String> get(String path, String jwt) {
    return restTemplate.exchange(
        "http://localhost:" + port + path,
        HttpMethod.GET,
        new HttpEntity<>(jwt == null ? new HttpHeaders() : headers(jwt)),
        String.class);
  }

  private static HttpHeaders headers(String jwt) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    return headers;
  }

  private ResponseEntity<String> post(String path, String body, String jwt, String idempotencyKey) {
    return post(path, body, jwt, idempotencyKey, null);
  }

  private ResponseEntity<String> post(
      String path, String body, String jwt, String idempotencyKey, String correlationId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(jwt);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    if (correlationId != null) {
      headers.set(CorrelationIds.HEADER_NAME, correlationId);
    }
    return restTemplate.exchange(
        "http://localhost:" + port + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  @TestConfiguration
  static class TestClockConfiguration {
    @Bean
    @Primary
    MutableClock mutablePolicyClock() {
      return new MutableClock(Instant.now(), ZoneOffset.UTC);
    }
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
      this.instant = new AtomicReference<>(instant);
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
      return new MutableClock(instant.get(), newZone);
    }

    @Override
    public Instant instant() {
      return instant.get();
    }

    void setInstant(Instant value) {
      instant.set(value);
    }
  }
}
