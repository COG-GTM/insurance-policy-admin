package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
      "pas.batch.premium.skip-limit=0",
      "pas.batch.premium.grid-size=1",
      "pas.batch.premium.concurrency=1"
    })
class PremiumSkipLimitIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;

  @Autowired
  @Qualifier("premiumRecalculationJob")
  private Job premiumJob;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @BeforeEach
  void clearFixtures() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS premium_skip_limit_failure ON pas.premiums");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS pas.reject_skip_limit_policy()");
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
  }

  @Test
  void failsJobAndRecordsSharedFailureWhenSkipLimitIsExceeded(CapturedOutput output)
      throws Exception {
    jdbcTemplate.update(
        "INSERT INTO pas.policies "
            + "(policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id) "
            + "VALUES (?, 'AUT', 'AC', DATE '2024-01-01', DATE '2025-01-01', 'HOLDER0001')",
        "POL000000601");
    jdbcTemplate.execute(
        "CREATE FUNCTION pas.reject_skip_limit_policy() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION USING ERRCODE = '23514'; END $$");
    jdbcTemplate.execute(
        "CREATE TRIGGER premium_skip_limit_failure BEFORE INSERT OR UPDATE ON pas.premiums "
            + "FOR EACH ROW EXECUTE FUNCTION pas.reject_skip_limit_policy()");
    double failuresBefore =
        meterRegistry.find("pas.batch.job.failures").tag("job", "premiumRecalculationJob").counter()
                == null
            ? 0
            : meterRegistry
                .find("pas.batch.job.failures")
                .tag("job", "premiumRecalculationJob")
                .counter()
                .count();

    JobExecution execution =
        jobLauncher.run(
            premiumJob,
            new JobParametersBuilder()
                .addString("businessDate", LocalDate.of(2024, 10, 25).toString(), true)
                .addLong("rerun", 601L, true)
                .toJobParameters());

    assertThat(execution.getStatus().name()).isEqualTo("FAILED");
    assertThat(
            meterRegistry
                .get("pas.batch.job.failures")
                .tag("job", "premiumRecalculationJob")
                .counter()
                .count())
        .isEqualTo(failuresBefore + 1);
    assertThat(output.getOut() + output.getErr()).contains("pas.batch.job.failed");
  }
}
