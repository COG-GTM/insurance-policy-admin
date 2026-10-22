package com.acme.insurance.pas.policy.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import com.acme.insurance.pas.policy.dev.FacadeSeedLoader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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

/** AB-526 route states: renewal in SHADOW (pas_shadow only), endorsement LEGACY (503). */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pas.routing.renewal=shadow", "pas.routing.endorsement=legacy"})
@ActiveProfiles("it")
class LifecycleShadowRouteIT {
  private static final String POLICY = "T00000000777";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private FacadeSeedLoader seedLoader;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> "file:" + PolicyJwtTestSupport.PUBLIC_KEY_FILE);
  }

  @TestConfiguration
  static class NoClaims {
    @Bean
    ClaimsHistoryPort claimsHistoryPort() {
      return new ClaimsHistoryPort() {
        @Override
        public int countOpenClaims(String policyNumber) {
          return 0;
        }

        @Override
        public ClaimsSummary summarizeSince(String policyNumber, LocalDate since) {
          return new ClaimsSummary(0, BigDecimal.ZERO);
        }
      };
    }
  }

  @BeforeEach
  void seed() {
    seedLoader.load();
    jdbc.update(
        """
        INSERT INTO pas.policies (
            policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id,
            agent_code, branch_code, total_premium, deductible, coverage_limit, inception_date,
            renewal_count, uw_status, risk_score, web_indicator, api_flag, last_updated, updated_by)
        SELECT ?, policy_type, 'AC', ?, ?, policyholder_id, agent_code, branch_code, 1000.00,
               deductible, coverage_limit, ?, 0, 'AP', risk_score, web_indicator, api_flag, now(), 'IT'
        FROM pas.policies WHERE policy_number = 'POL-00000001'
        ON CONFLICT (policy_number) DO NOTHING
        """,
        POLICY,
        today().minusDays(335),
        today().plusDays(30),
        today().minusDays(335));
  }

  @Test
  void shadowRenewalRecordsResultWithoutChangingThePolicy() throws Exception {
    ResponseEntity<String> response =
        post("renewals", Map.of("termExpiryDate", today().plusDays(30).toString()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).contains("\"mode\":\"SHADOW\"");
    assertThat(
            jdbc.queryForObject(
                "SELECT expiry_date::text FROM pas.policies WHERE policy_number = ?",
                String.class,
                POLICY))
        .isEqualTo(today().plusDays(30).toString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pas.policy_renewals WHERE policy_number = ?",
                Integer.class,
                POLICY))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT new_premium FROM pas_shadow.renewal_results WHERE policy_number = ? AND outcome = 'RENEWED'",
                BigDecimal.class,
                POLICY))
        .isEqualByComparingTo("1050.00");
  }

  @Test
  void shadowTablesHaveNoForeignKeysToPasAndNoPiiColumns() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'pas_shadow' AND constraint_type = 'FOREIGN KEY'",
                Integer.class))
        .isZero();
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'pas_shadow' AND table_name IN ('renewal_results', 'endorsement_results')",
            String.class);
    assertThat(columns)
        .noneMatch(
            column ->
                column.contains("name")
                    || column.contains("addr")
                    || column.contains("tax")
                    || column.contains("birth")
                    || column.contains("email")
                    || column.contains("phone"));
  }

  @Test
  void legacyEndorsementRouteIsUnavailable() throws Exception {
    ResponseEntity<String> response =
        post("endorsements", Map.of("type", "CAN", "effectiveDate", today().toString()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("ROUTE_NOT_SERVING");
  }

  private ResponseEntity<String> post(String resource, Map<String, Object> body) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(
        PolicyJwtTestSupport.mint(
            "policy:renew policy:endorse", "shadow-tester", Map.of("book", "ALL")));
    return restTemplate.exchange(
        "/api/v1/policies/" + POLICY + "/" + resource,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
