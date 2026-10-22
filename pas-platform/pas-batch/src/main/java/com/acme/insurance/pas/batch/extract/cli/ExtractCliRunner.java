package com.acme.insurance.pas.batch.extract.cli;

import com.acme.insurance.pas.batch.extract.routing.ExtractRouteProperties;
import com.acme.insurance.pas.batch.extract.routing.RouteMode;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pas.batch.cli.job")
public class ExtractCliRunner implements CommandLineRunner, ExitCodeGenerator {
  private final JobLauncher jobLauncher;
  private final Job dailyExtractJob;
  private final Job monthlyExposureJob;
  private final ExtractRouteProperties routes;
  private final String jobName;
  private final String requestedDate;
  private final String rerun;
  private final ZoneId zone;
  private int exitCode = 1;

  public ExtractCliRunner(
      JobLauncher jobLauncher,
      Job dailyExtractJob,
      Job monthlyExposureJob,
      ExtractRouteProperties routes,
      @Value("${pas.batch.cli.job}") String jobName,
      @Value("${pas.batch.cli.business-date:}") String requestedDate,
      @Value("${pas.batch.cli.rerun:}") String rerun,
      @Value("${pas.batch.extract.zone:America/New_York}") String zone) {
    this.jobLauncher = jobLauncher;
    this.dailyExtractJob = dailyExtractJob;
    this.monthlyExposureJob = monthlyExposureJob;
    this.routes = routes;
    this.jobName = jobName;
    this.requestedDate = requestedDate;
    this.rerun = rerun;
    this.zone = ZoneId.of(zone);
  }

  @Override
  public void run(String... args) throws Exception {
    Job job;
    if (jobName.equals("dailyExtractJob")) {
      job = dailyExtractJob;
    } else if (jobName.equals("monthlyExposureJob")) {
      job = monthlyExposureJob;
    } else {
      return;
    }
    LocalDate businessDate =
        requestedDate.isBlank() ? LocalDate.now(zone) : LocalDate.parse(requestedDate);
    JobParametersBuilder builder =
        new JobParametersBuilder().addString("businessDate", businessDate.toString(), true);
    if (!rerun.isBlank()) {
      builder.addLong("rerun", Long.parseLong(rerun), true);
    }
    try {
      JobParameters parameters = builder.toJobParameters();
      JobExecution execution = jobLauncher.run(job, parameters);
      if (routes.forJob(jobName) != RouteMode.RETIRED
          && execution.getStatus() == BatchStatus.COMPLETED) {
        exitCode = 0;
      }
    } catch (Exception exception) {
      exitCode = 1;
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
