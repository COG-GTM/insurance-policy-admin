package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RatingRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.premium-batch=new",
      "pas.batch.premium.grid-size=2",
      "pas.batch.premium.concurrency=2",
      "pas.batch.premium.chunk-size=1",
      "pas.batch.premium.report-dir=${java.io.tmpdir}/pas-batch-premium-it"
    })
class PremiumRecalculationJobIT {
  private static final String REPORT_ROOT =
      Path.of(System.getProperty("java.io.tmpdir"), "pas-batch-premium-it").toString();
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2024, 10, 15);

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;

  @Autowired
  @Qualifier("premiumRecalculationJob")
  private Job premiumJob;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PremiumCalculator premiumCalculator;
  @Autowired private MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @BeforeEach
  void clearPremiumFixture() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS premium_reject_policy ON pas.premiums");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS pas.reject_premium_policy()");
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
  }

  @Test
  void calculatesOnlyActivePoliciesAndLeavesPolicyPremiumUnchanged() throws Exception {
    insertPolicy("POL000000101", "AUT", "AC", "77.00");
    insertPolicy("POL000000102", "HOM", "PN", "88.00");

    JobExecution execution = launch(BUSINESS_DATE, 1001);

    assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT total_premium FROM pas.premiums WHERE policy_number = ?",
                java.math.BigDecimal.class,
                "POL000000101"))
        .isEqualByComparingTo(
            premiumCalculator
                .calculate(new RatingRequest("AUT", BUSINESS_DATE, null, null))
                .totalPremium());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT total_premium FROM pas.policies WHERE policy_number = ?",
                java.math.BigDecimal.class,
                "POL000000101"))
        .isEqualByComparingTo("77.00");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT policies_read FROM pas_batch.premium_run_summary WHERE job_execution_id = ?",
                Long.class,
                execution.getId()))
        .isEqualTo(1L);

    Path report = reportPath(BUSINESS_DATE, execution.getId());
    String content = Files.readString(report);
    assertThat(content)
        .contains("ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: 20241015")
        .contains("POL000000101 PREMIUM: 000000904.75")
        .contains("TOTAL POLICIES READ:    0000001");
    assertThat(content.lines()).allMatch(line -> line.length() == 132);
  }

  @Test
  void rerunReplacesStalePremiumRowsIdempotently() {
    insertPolicy("POL000000201", "AUT", "AC", "77.00");

    JobExecution first = launch(BUSINESS_DATE.plusDays(1), 2001);
    jdbcTemplate.update(
        "UPDATE pas.premiums SET total_premium = 1, calc_by = 'STALE' " + "WHERE policy_number = ?",
        "POL000000201");
    JobExecution second = launch(BUSINESS_DATE.plusDays(1), 2002);

    assertThat(first.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(second.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT total_premium FROM pas.premiums WHERE policy_number = ?",
                java.math.BigDecimal.class,
                "POL000000201"))
        .isEqualByComparingTo("904.75");
  }

  @Test
  void skipsDataIntegrityItemAndEmitsSummaryMetricAndAlert(CapturedOutput output) {
    insertPolicy("POL000000301", "AUT", "AC", "77.00");
    insertPolicy("POL000000302", "HOM", "AC", "88.00");
    insertPolicy("POL000000303", "COM", "AC", "99.00");
    jdbcTemplate.execute(
        "CREATE FUNCTION pas.reject_premium_policy() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.policy_number = 'POL000000302' THEN "
            + "RAISE EXCEPTION USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
    jdbcTemplate.execute(
        "CREATE TRIGGER premium_reject_policy BEFORE INSERT OR UPDATE ON pas.premiums "
            + "FOR EACH ROW EXECUTE FUNCTION pas.reject_premium_policy()");

    JobExecution execution = launch(BUSINESS_DATE.plusDays(2), 3001);

    assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_ERRORS");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.premium_run_item WHERE status = 'UPDATED'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT error_type FROM pas_batch.premium_run_item WHERE policy_number = ?",
                String.class,
                "POL000000302"))
        .isEqualTo("DataIntegrityViolationException");
    assertThat(meterRegistry.get("pas.batch.premium.run.errors").counter().count()).isEqualTo(1);
    assertThat(output.getOut() + output.getErr())
        .contains("PREMIUM_BATCH_FAILED")
        .contains("pas.batch.premium.policy.skipped")
        .contains("POL000000302")
        .doesNotContain("HOLDER0001");
  }

  private JobExecution launch(LocalDate date, long rerun) {
    JobParameters parameters =
        new JobParametersBuilder()
            .addString("businessDate", date.toString(), true)
            .addLong("rerun", rerun, true)
            .toJobParameters();
    try {
      return jobLauncher.run(premiumJob, parameters);
    } catch (Exception exception) {
      throw new AssertionError("Premium job launch failed", exception);
    }
  }

  private void insertPolicy(String policyNumber, String policyType, String status, String premium) {
    jdbcTemplate.update(
        "INSERT INTO pas.policies "
            + "(policy_number, policy_type, policy_status, effective_date, expiry_date, "
            + "policyholder_id, total_premium) VALUES (?, ?, ?, DATE '2024-01-01', "
            + "DATE '2025-01-01', ?, ?)",
        policyNumber,
        policyType,
        status,
        "HOLDER0001",
        new java.math.BigDecimal(premium));
  }

  private static Path reportPath(LocalDate date, long executionId) {
    return Path.of(
        REPORT_ROOT,
        "premiumRecalculationJob",
        date.toString(),
        Long.toString(executionId),
        "PREMRPT.D" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + ".txt");
  }
}
