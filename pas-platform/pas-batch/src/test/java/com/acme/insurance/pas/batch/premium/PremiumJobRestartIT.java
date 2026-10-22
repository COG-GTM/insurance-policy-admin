package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
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
      "pas.batch.premium.chunk-size=1",
      "pas.batch.premium.report-dir=${java.io.tmpdir}/pas-batch-premium-restart-it"
    })
class PremiumJobRestartIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private JobLauncher jobLauncher;
  @Autowired private JobExplorer jobExplorer;

  @Autowired
  @Qualifier("premiumRecalculationJob")
  private Job premiumJob;

  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void prepareDatabase(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
  }

  @BeforeEach
  void clearFixtures() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS premium_restart_failure ON pas_shadow.premiums");
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS pas.reject_restart_policy()");
    jdbcTemplate.update("DELETE FROM pas.premiums");
    jdbcTemplate.update("DELETE FROM pas_shadow.premiums");
    jdbcTemplate.update("DELETE FROM pas_shadow.premium_run_report");
    jdbcTemplate.update("DELETE FROM pas.policies");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_item");
    jdbcTemplate.update("DELETE FROM pas_batch.premium_run_summary");
  }

  @Test
  void restartsFailedPartitionAfterRemovingTransientDatabaseFailure() throws Exception {
    for (String number :
        new String[] {"POL000000501", "POL000000502", "POL000000503", "POL000000504"}) {
      jdbcTemplate.update(
          "INSERT INTO pas.policies "
              + "(policy_number, policy_type, policy_status, effective_date, expiry_date, policyholder_id) "
              + "VALUES (?, 'AUT', 'AC', DATE '2024-01-01', DATE '2025-01-01', 'HOLDER0001')",
          number);
    }
    jdbcTemplate.execute(
        "CREATE FUNCTION pas.reject_restart_policy() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.policy_number = 'POL000000504' THEN "
            + "RAISE EXCEPTION USING ERRCODE = '57014'; END IF; RETURN NEW; END $$");
    jdbcTemplate.execute(
        "CREATE TRIGGER premium_restart_failure BEFORE INSERT OR UPDATE ON pas_shadow.premiums "
            + "FOR EACH ROW EXECUTE FUNCTION pas.reject_restart_policy()");
    JobParameters parameters =
        new JobParametersBuilder()
            .addString("businessDate", LocalDate.of(2024, 10, 20).toString(), true)
            .addLong("rerun", 5004L, true)
            .toJobParameters();

    JobExecution failed = jobLauncher.run(premiumJob, parameters);
    assertThat(failed.getStatus().name()).isEqualTo("FAILED");
    Set<String> completedPartitions =
        failed.getStepExecutions().stream()
            .filter(step -> step.getStepName().startsWith("premiumWorkerStep"))
            .filter(step -> step.getStatus() == BatchStatus.COMPLETED)
            .map(org.springframework.batch.core.StepExecution::getStepName)
            .collect(Collectors.toSet());
    assertThat(completedPartitions).hasSize(1);
    assertThat(failed.getStepExecutions())
        .filteredOn(step -> step.getStepName().equals("premiumRouteGuardStep"))
        .singleElement()
        .satisfies(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED));
    LocalDate businessDate = LocalDate.of(2024, 10, 20);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_shadow.premiums WHERE business_date = ?",
                Integer.class,
                businessDate))
        .isEqualTo(3);

    jdbcTemplate.execute("DROP TRIGGER premium_restart_failure ON pas_shadow.premiums");
    JobExecution restarted = jobLauncher.run(premiumJob, parameters);

    assertThat(restarted.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(restarted.getJobInstance().getInstanceId())
        .isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_shadow.premiums WHERE business_date = ?",
                Integer.class,
                businessDate))
        .isEqualTo(4);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isZero();
    assertThat(
            jobExplorer.getJobExecutions(failed.getJobInstance()).stream()
                .flatMap(execution -> execution.getStepExecutions().stream())
                .filter(step -> step.getStepName().equals("premiumRouteGuardStep"))
                .filter(step -> step.getId() != null)
                .count())
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.premium_run_item WHERE status = 'UPDATED'",
                Integer.class))
        .isEqualTo(4);
    assertThat(restarted.getStepExecutions())
        .filteredOn(step -> completedPartitions.contains(step.getStepName()))
        .allMatch(step -> step.getId() == null);
    assertThat(restarted.getStepExecutions())
        .filteredOn(step -> step.getStepName().startsWith("premiumWorkerStep"))
        .filteredOn(step -> step.getId() != null)
        .hasSize(1)
        .allMatch(step -> step.getReadCount() < 2);
    assertThat(jobExplorer.getJobInstance(restarted.getJobInstance().getInstanceId())).isNotNull();
  }
}
