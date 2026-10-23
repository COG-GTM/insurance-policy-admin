package com.acme.insurance.pas.batch.migration.delta;

import java.util.Date;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "pas.migration",
    name = {"enabled", "delta-sync.scheduler-enabled"},
    havingValue = "true")
public final class DeltaSyncScheduler {
  private final JobLauncher jobLauncher;
  private final Job deltaSyncJob;

  public DeltaSyncScheduler(JobLauncher jobLauncher, @Qualifier("deltaSyncJob") Job deltaSyncJob) {
    this.jobLauncher = jobLauncher;
    this.deltaSyncJob = deltaSyncJob;
  }

  @Scheduled(fixedDelayString = "${pas.migration.delta-sync.fixed-delay:1m}")
  public void launch() throws Exception {
    jobLauncher.run(
        deltaSyncJob,
        new JobParametersBuilder()
            .addLong("runId", System.currentTimeMillis())
            .addDate("scheduledAt", new Date())
            .toJobParameters());
  }
}
