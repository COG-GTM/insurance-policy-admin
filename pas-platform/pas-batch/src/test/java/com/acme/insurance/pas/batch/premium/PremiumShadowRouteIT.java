package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.premium-batch=shadow",
      "pas.batch.premium.grid-size=2",
      "pas.batch.premium.concurrency=1",
      "pas.batch.premium.report-dir=${java.io.tmpdir}/pas-batch-premium-shadow-it"
    })
class PremiumShadowRouteIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;

  @Autowired
  @Qualifier("premiumRecalculationJob")
  private Job premiumJob;

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @BeforeEach
  void resetTables() {
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas_shadow.premiums");
    jdbcTemplate.update("DELETE FROM pas_shadow.premium_run_report");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
  }

  @Test
  void writesShadowPremiumAndReportOnlyAndReplacesBusinessDate() throws Exception {
    insertPolicy("POL000000401");
    LocalDate date = LocalDate.of(2024, 10, 15);

    JobExecution first = launch(date, 1);
    JobExecution second = launch(date, 2);

    assertThat(first.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(second.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_shadow.premiums WHERE business_date = ?",
                Integer.class,
                date))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_shadow.premium_run_report WHERE business_date = ?",
                Integer.class,
                date))
        .isGreaterThan(3);
  }

  private JobExecution launch(LocalDate date, long rerun) throws Exception {
    return jobLauncher.run(
        premiumJob,
        new JobParametersBuilder()
            .addString("businessDate", date.toString(), true)
            .addLong("rerun", rerun, true)
            .toJobParameters());
  }

  private void insertPolicy(String policyNumber) {
    jdbcTemplate.update(
        "INSERT INTO pas.policies "
            + "(policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id) "
            + "VALUES (?, 'AUT', 'AC', DATE '2024-01-01', DATE '2025-01-01', 'HOLDER0001')",
        policyNumber);
  }
}
