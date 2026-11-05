package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs job {@code premiumRecalculation} against PostgreSQL with the Flyway migrations: COBOL parity
 * of PREMIUMS rows and report, same-day rerun, per-policy errors and restart after failure. Three
 * partitions of two-policy chunks, so even the small fixtures cross chunk and partition boundaries.
 */
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration/postgresql",
      "pas.premium.partitions=3",
      "pas.premium.chunk-size=2"
    })
@Import(PremiumItSupport.Postgres.class)
class PremiumJobIT {

  @TempDir static Path reports;

  @DynamicPropertySource
  static void reportDir(DynamicPropertyRegistry registry) {
    registry.add("pas.premium.report-dir", () -> reports.toString());
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier(PremiumJobConfiguration.JOB)
  Job job;

  @Autowired JdbcTemplate jdbc;

  PremiumItSupport db;

  @BeforeEach
  void clean() {
    db = new PremiumItSupport(jdbc);
    db.reset();
  }

  @Test
  void premiumsRowsAndReportMatchTheCobolGoldenFile() throws Exception {
    JsonNode golden = PremiumGolden.load("report-totals");
    db.policy("POL-00000036", "AUT", "2025-01-01", "2026-01-01", "AC");
    db.policy("POL-00000037", "LIF", "2025-01-01", "2026-01-01", "AC");
    db.policy("POL-00000038", "HLT", "2025-01-01", "2026-01-01", "AC");
    db.policy("POL-00000039", "AUT", "2025-01-01", "2026-01-01", "CA");

    JobExecution run = launcher.run(job, params("2025-03-01", null));

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(run.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
    List<Map<String, Object>> rows = db.premiums();
    assertThat(rows).hasSize(golden.path("effects").size());
    for (int i = 0; i < rows.size(); i++) {
      JsonNode effect = golden.path("effects").get(i);
      Map<String, Object> row = rows.get(i);
      assertThat(row.get("policy_number")).isEqualTo(effect.at("/text/POLICY_NUMBER").asText());
      assertThat(row.get("calc_by")).isEqualTo("PREMBAT");
      assertThat(row.get("installment_code")).isEqualTo("AN");
      assertThat(row.get("calc_date")).hasToString("2025-03-01");
      assertThat(row.get("term_effective_date")).hasToString("2025-01-01");
      assertThat(row.get("term_expiry_date")).hasToString("2026-01-01");
      for (String column :
          List.of(
              "COVERAGE_SEQ",
              "BASE_RATE",
              "TERRITORY_FACTOR",
              "CLASS_FACTOR",
              "EXPERIENCE_MOD",
              "SCHEDULE_MOD",
              "DISCOUNT_PCT",
              "SURCHARGE_AMT",
              "TAX_AMT",
              "TOTAL_PREMIUM")) {
        assertThat(new BigDecimal(row.get(column.toLowerCase()).toString()))
            .as(column)
            .isEqualByComparingTo(effect.at("/numbers/" + column).asText());
      }
    }
    assertThat(report(run)).containsExactlyElementsOf(PremiumGolden.report("report-totals"));
    assertThat(run.getExecutionContext().getLong(PremiumJobConfiguration.READ)).isEqualTo(3);
  }

  @Test
  void aSameDayRerunReplacesTheRowsInsteadOfFailingOnThePrimaryKey() throws Exception {
    db.book(7);
    JobExecution first = launcher.run(job, params("2025-03-01", null));
    jdbc.update("UPDATE acmeins.premiums SET total_premium = 1.00");

    JobExecution rerun = launcher.run(job, params("2025-03-01", 1L));

    assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(db.premiums()).hasSize(7);
    assertThat(
            jdbc.queryForObject(
                "SELECT MIN(total_premium) FROM acmeins.premiums", BigDecimal.class))
        .isEqualByComparingTo("439.00");
    assertThat(report(rerun)).last().isEqualTo("TOTAL ERRORS:           0000000");
    assertThat(report(rerun)).contains("TOTAL POLICIES UPDATED: 0000007");
  }

  @Test
  void aPolicyTheDatabaseRejectsIsAnErrorAndTheRunContinues() throws Exception {
    db.policy("POL-00000031", "AUT", "2025-01-01", "2026-01-01", "AC");
    db.policy("POL-00000032", "HOM", "2025-01-01", "2026-01-01", "AC");
    db.policy("POL-00000033", "COM", "2025-01-01", "2026-01-01", "AC");
    db.rejectPremiumsOf("POL-00000032", "23505");

    JobExecution run = launcher.run(job, params("2025-03-01", null));

    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(run.getExitStatus().getExitCode())
        .isEqualTo(PremiumJobAlerting.COMPLETED_WITH_ERRORS);
    assertThat(db.premiums())
        .extracting(r -> r.get("policy_number"))
        .containsExactly("POL-00000031", "POL-00000033");
    List<String> report = report(run);
    assertThat(report.subList(report.size() - 3, report.size()))
        .containsExactly(
            "TOTAL POLICIES READ:    0000003",
            "TOTAL POLICIES UPDATED: 0000002",
            "TOTAL ERRORS:           0000001");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_code FROM pasapp.premium_run_item WHERE status = 'E'", String.class))
        .isEqualTo("DuplicateKeyException");
  }

  @Test
  void aFailedRunRestartsFromTheLastCommitAndReportsTheWholeBook() throws Exception {
    db.book(12);
    db.rejectPremiumsOf("POL-00000110", "P0001");

    JobExecution failed = launcher.run(job, params("2025-03-01", null));

    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    int writtenBeforeRestart = db.premiums().size();
    assertThat(writtenBeforeRestart).isBetween(1, 11);
    db.acceptAllPremiums();

    JobExecution restarted = launcher.run(job, failed.getJobParameters());

    assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(restarted.getJobInstance().getInstanceId())
        .isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(db.premiums()).hasSize(12);
    long reread =
        restarted.getStepExecutions().stream()
            .filter(s -> s.getStepName().startsWith(PremiumJobConfiguration.PARTITION_STEP + ":"))
            .mapToLong(StepExecution::getReadCount)
            .sum();
    assertThat(reread).as("committed chunks are not read again").isLessThan(12);
    assertThat(report(restarted))
        .contains("TOTAL POLICIES READ:    0000012", "TOTAL ERRORS:           0000000");
  }

  @Test
  void aRestartRefusesToMixRatesChangedSinceTheFailedRun() throws Exception {
    db.book(12);
    db.rejectPremiumsOf("POL-00000110", "P0001");
    JobExecution failed = launcher.run(job, params("2025-03-01", null));
    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    db.acceptAllPremiums();
    jdbc.update("UPDATE pasapp.base_rates SET base_rate = base_rate + 1 WHERE policy_type = 'AUT'");
    try {
      JobExecution restarted = launcher.run(job, failed.getJobParameters());

      assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
      assertThat(restarted.getStepExecutions()).isEmpty();
      assertThat(restarted.getAllFailureExceptions())
          .anySatisfy(e -> assertThat(e).hasMessageContaining("same-day rerun"));

      JobExecution rerun = launcher.run(job, params("2025-03-01", 1L));
      assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
      assertThat(db.premiums()).hasSize(12);
    } finally {
      jdbc.update(
          "UPDATE pasapp.base_rates SET base_rate = base_rate - 1 WHERE policy_type = 'AUT'");
    }
  }

  @Test
  void anEmptyBookCompletesWithZeroTotals() throws Exception {
    JobExecution run = launcher.run(job, params("2025-03-02", null));
    assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(report(run)).contains("TOTAL POLICIES READ:    0000000");
  }

  private static JobParameters params(String businessDate, Long rerun) {
    JobParametersBuilder builder =
        new JobParametersBuilder()
            .addString(PremiumJobParameters.BUSINESS_DATE, businessDate)
            .addLong("test.run", System.nanoTime());
    if (rerun != null) {
      builder.addLong(PremiumJobParameters.RERUN, rerun);
    }
    return builder.toJobParameters();
  }

  private static List<String> report(JobExecution run) throws Exception {
    Path file = Path.of(run.getExecutionContext().getString(PremiumJobConfiguration.REPORT_FILE));
    return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
        .lines()
        .map(PremiumGolden::escapeAndTrim)
        .toList();
  }
}
