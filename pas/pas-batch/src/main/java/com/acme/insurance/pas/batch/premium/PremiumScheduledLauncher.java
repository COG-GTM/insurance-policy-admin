package com.acme.insurance.pas.batch.premium;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner;

/**
 * The scheduler's launch path ({@code java -jar pas-batch-exec.jar} with {@code
 * PAS_BATCH_JOB=premiumRecalculation}). A launch of {@code premiumRecalculation} without a {@code
 * businessDate} (the CronJob and its pod retry) restarts today's latest execution when it is {@code
 * FAILED} or {@code STOPPED}; otherwise Spring Boot asks the incrementer for a new instance.
 * Explicit parameters and every other job are launched as Spring Boot does.
 */
public class PremiumScheduledLauncher extends JobLauncherApplicationRunner {

  private final PremiumJobParameters premiumParameters;

  public PremiumScheduledLauncher(
      JobLauncher launcher,
      JobExplorer explorer,
      JobRepository repository,
      PremiumJobParameters premiumParameters) {
    super(launcher, explorer, repository);
    this.premiumParameters = premiumParameters;
  }

  @Override
  protected void execute(Job job, JobParameters parameters)
      throws JobExecutionAlreadyRunningException,
          JobRestartException,
          JobInstanceAlreadyCompleteException,
          JobParametersInvalidException {
    JobParameters launch = parameters;
    if (PremiumJobConfiguration.JOB.equals(job.getName())
        && parameters.getString(PremiumJobParameters.BUSINESS_DATE) == null) {
      launch = premiumParameters.restartableRunOfToday().orElse(parameters);
    }
    super.execute(job, launch);
  }
}
