package com.acme.insurance.pas.batch.schedule;

import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Component;

/**
 * Launches an extract job for a business date. {@code launchedAt} makes every launch a new job
 * instance, so a rerun for the same business date is allowed and overwrites that day's files.
 */
@Component
public class ExtractJobLauncher {

  private final JobLauncher jobLauncher;
  private final Clock clock;

  public ExtractJobLauncher(JobLauncher jobLauncher) {
    this.jobLauncher = jobLauncher;
    this.clock = Clock.systemUTC();
  }

  public static JobParameters parameters(LocalDate businessDate, LocalDateTime launchedAt) {
    return new JobParametersBuilder()
        .addLocalDate(ExtractOutputs.BUSINESS_DATE, businessDate)
        .addLocalDateTime("launchedAt", launchedAt)
        .toJobParameters();
  }

  public JobExecution launch(Job job, LocalDate businessDate)
      throws JobExecutionAlreadyRunningException,
          JobRestartException,
          JobInstanceAlreadyCompleteException,
          JobParametersInvalidException {
    return jobLauncher.run(job, parameters(businessDate, LocalDateTime.now(clock)));
  }
}
