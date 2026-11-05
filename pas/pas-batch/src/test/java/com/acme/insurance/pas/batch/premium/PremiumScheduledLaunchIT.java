package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Launches job {@code premiumRecalculation} the way the CronJob and its pod retry do: through the
 * application's {@link PremiumScheduledLauncher} (Spring Boot's runner) with no arguments. A failed
 * run is restarted (same instance, committed chunks kept); a launch after a completed run is a
 * same-day rerun.
 */
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration/postgresql",
      "pas.premium.partitions=3",
      "pas.premium.chunk-size=2"
    })
@Import({PremiumItSupport.Postgres.class, PremiumScheduledLaunchIT.FixedClock.class})
class PremiumScheduledLaunchIT {

  // 06:00 UTC on 2 March is 01:00 on 2 March in New York, the PAS0050 slot.
  static final Instant SCHEDULED = Instant.parse("2025-03-02T06:00:00Z");
  static final String TODAY = "2025-03-02";

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(SCHEDULED, ZoneOffset.UTC);
    }
  }

  @TempDir static Path reports;

  @DynamicPropertySource
  static void reportDir(DynamicPropertyRegistry registry) {
    registry.add("pas.premium.report-dir", () -> reports.toString());
  }

  @Autowired JobLauncher launcher;
  @Autowired JobExplorer explorer;
  @Autowired JobRepository repository;

  @Autowired
  @Qualifier(PremiumJobConfiguration.JOB)
  Job job;

  @Autowired PremiumJobParameters parameters;
  @Autowired JdbcTemplate jdbc;

  PremiumItSupport db;

  @BeforeEach
  void clean() {
    db = new PremiumItSupport(jdbc);
    db.reset();
    for (String table :
        List.of(
            "step_execution_context",
            "step_execution",
            "job_execution_context",
            "job_execution_params",
            "job_execution",
            "job_instance")) {
      jdbc.update("DELETE FROM pas_batch.batch_" + table);
    }
  }

  @Test
  void thePodRetryRestartsTheFailedRunAndALaterLaunchIsASameDayRerun() throws Exception {
    db.book(12);
    db.rejectPremiumsOf("POL-00000110", "P0001");

    JobExecution failed = scheduledLaunch();

    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(failed.getJobParameters().getString(PremiumJobParameters.BUSINESS_DATE))
        .isEqualTo(TODAY);
    assertThat(failed.getJobParameters().getLong(PremiumJobParameters.RERUN)).isNull();
    assertThat(job.getJobParametersIncrementer().getNext(failed.getJobParameters()))
        .as("an explicit next instance (JobOperator.startNextInstance) is still a rerun")
        .isEqualTo(
            new JobParametersBuilder()
                .addString(PremiumJobParameters.BUSINESS_DATE, TODAY)
                .addLong(PremiumJobParameters.RERUN, 1L)
                .toJobParameters());
    db.acceptAllPremiums();

    JobExecution retried = scheduledLaunch();

    assertThat(retried.getJobInstance().getInstanceId())
        .as("the retry restarts the failed instance")
        .isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(retried.getId()).isNotEqualTo(failed.getId());
    assertThat(retried.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(explorer.getLastJobExecution(failed.getJobInstance()).getStatus())
        .isEqualTo(BatchStatus.COMPLETED);
    assertThat(retried.getExecutionContext().getString(PremiumRunRaters.RATING_VERSION))
        .isEqualTo(failed.getExecutionContext().getString(PremiumRunRaters.RATING_VERSION));
    long reread =
        retried.getStepExecutions().stream()
            .filter(s -> s.getStepName().startsWith(PremiumJobConfiguration.PARTITION_STEP + ":"))
            .mapToLong(StepExecution::getReadCount)
            .sum();
    assertThat(reread).as("committed chunks are not read again").isLessThan(12);
    assertThat(db.premiums()).hasSize(12);
    assertThat(retried.getExecutionContext().getLong(PremiumJobConfiguration.READ)).isEqualTo(12);

    JobExecution rerun = scheduledLaunch();

    assertThat(rerun.getJobInstance().getInstanceId())
        .isNotEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(rerun.getJobParameters().getString(PremiumJobParameters.BUSINESS_DATE))
        .isEqualTo(TODAY);
    assertThat(rerun.getJobParameters().getLong(PremiumJobParameters.RERUN)).isEqualTo(1L);
    assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(db.premiums()).hasSize(12);
    assertThat(explorer.getJobInstances(PremiumJobConfiguration.JOB, 0, 10)).hasSize(2);
  }

  @Test
  void theRetryOfAFailedRunRefusesRatesThatChangedSinceTheFailedExecution() throws Exception {
    db.book(6);
    db.rejectPremiumsOf("POL-00000104", "P0001");
    JobExecution failed = scheduledLaunch();
    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    db.acceptAllPremiums();
    jdbc.update("UPDATE pasapp.base_rates SET base_rate = base_rate + 1 WHERE policy_type = 'AUT'");
    try {
      JobExecution retried = scheduledLaunch();

      assertThat(retried.getJobInstance().getInstanceId())
          .isEqualTo(failed.getJobInstance().getInstanceId());
      assertThat(retried.getStatus()).isEqualTo(BatchStatus.FAILED);
      assertThat(retried.getStepExecutions()).isEmpty();
      assertThat(retried.getExitStatus().getExitDescription()).contains("same-day rerun");
    } finally {
      jdbc.update(
          "UPDATE pasapp.base_rates SET base_rate = base_rate - 1 WHERE policy_type = 'AUT'");
    }
  }

  @Test
  void theRetryRestartsTheRunThatFailedLastEvenIfANewerInstanceExists() throws Exception {
    db.book(6);
    db.rejectPremiumsOf("POL-00000104", "P0001");
    JobExecution first = scheduledLaunch();
    assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
    db.acceptAllPremiums();
    JobExecution rerun =
        launcher.run(
            job,
            new JobParametersBuilder()
                .addString(PremiumJobParameters.BUSINESS_DATE, TODAY)
                .addLong(PremiumJobParameters.RERUN, 1L)
                .toJobParameters());
    assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    db.rejectPremiumsOf("POL-00000104", "P0001");
    JobExecution manualRestart =
        launcher.run(job, new JobParameters(first.getJobParameters().getIdentifyingParameters()));
    assertThat(manualRestart.getJobInstance().getInstanceId())
        .isEqualTo(first.getJobInstance().getInstanceId());
    assertThat(manualRestart.getStatus()).isEqualTo(BatchStatus.FAILED);
    db.acceptAllPremiums();

    JobExecution retried = scheduledLaunch(first.getJobInstance());

    assertThat(retried.getId()).isNotEqualTo(manualRestart.getId());
    assertThat(retried.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(explorer.getJobInstances(PremiumJobConfiguration.JOB, 0, 10)).hasSize(2);
  }

  /** {@code java -jar pas-batch-exec.jar} with PAS_BATCH_JOB=premiumRecalculation and no args. */
  private JobExecution scheduledLaunch() throws Exception {
    launchWithoutArguments();
    List<JobInstance> instances = explorer.getJobInstances(PremiumJobConfiguration.JOB, 0, 1);
    return explorer.getLastJobExecution(instances.get(0));
  }

  private JobExecution scheduledLaunch(JobInstance instance) throws Exception {
    launchWithoutArguments();
    return explorer.getLastJobExecution(instance);
  }

  private void launchWithoutArguments() throws Exception {
    PremiumScheduledLauncher runner =
        new PremiumScheduledLauncher(launcher, explorer, repository, parameters);
    runner.setJobName(PremiumJobConfiguration.JOB);
    runner.setJobs(List.of(job));
    runner.run();
  }
}
