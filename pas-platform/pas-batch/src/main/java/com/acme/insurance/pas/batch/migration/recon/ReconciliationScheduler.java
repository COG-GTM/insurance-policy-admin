package com.acme.insurance.pas.batch.migration.recon;

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
    name = {"enabled", "recon.scheduler-enabled"},
    havingValue = "true")
public final class ReconciliationScheduler {
  private final JobLauncher jobLauncher;
  private final Job reconciliationJob;

  public ReconciliationScheduler(
      JobLauncher jobLauncher, @Qualifier("reconciliationJob") Job reconciliationJob) {
    this.jobLauncher = jobLauncher;
    this.reconciliationJob = reconciliationJob;
  }

  @Scheduled(cron = "${pas.migration.recon.schedule:0 0 3 * * *}")
  public void launch() throws Exception {
    long runId = System.currentTimeMillis();
    jobLauncher.run(
        reconciliationJob,
        new JobParametersBuilder()
            .addLong("runId", runId)
            .addDate("scheduledAt", new Date())
            .toJobParameters());
  }
}
