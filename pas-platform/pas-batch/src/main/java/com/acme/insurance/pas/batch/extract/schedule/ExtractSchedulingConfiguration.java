package com.acme.insurance.pas.batch.extract.schedule;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "pas.batch.extract.schedule.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ExtractSchedulingConfiguration {
  private static final Logger log = LoggerFactory.getLogger(ExtractSchedulingConfiguration.class);

  private final JobLauncher jobLauncher;
  private final Job dailyExtractJob;
  private final Job monthlyExposureJob;
  private final RouteFlags routes;
  private final ZoneId zone;

  public ExtractSchedulingConfiguration(
      JobLauncher jobLauncher,
      Job dailyExtractJob,
      Job monthlyExposureJob,
      RouteFlags routes,
      @Value("${pas.batch.extract.zone:America/New_York}") String zone) {
    this.jobLauncher = jobLauncher;
    this.dailyExtractJob = dailyExtractJob;
    this.monthlyExposureJob = monthlyExposureJob;
    this.routes = routes;
    this.zone = ZoneId.of(zone);
  }

  @Scheduled(
      cron = "${pas.batch.extract.schedule.daily-cron:0 0 23 * * *}",
      zone = "${pas.batch.extract.zone:America/New_York}")
  public void launchDailyExtract() {
    launch(dailyExtractJob, RouteFlags.DAILY_EXTRACT);
  }

  @Scheduled(
      cron = "${pas.batch.extract.schedule.monthly-cron:0 0 2 1 * *}",
      zone = "${pas.batch.extract.zone:America/New_York}")
  public void launchMonthlyExposure() {
    launch(monthlyExposureJob, RouteFlags.MONTHLY_EXPOSURE);
  }

  private void launch(Job job, String routeCapability) {
    if (routes.state(routeCapability) == RouteState.RETIRED) {
      log.atInfo()
          .addKeyValue("event", "pas.batch.extract.schedule.skipped")
          .addKeyValue("job", job.getName())
          .addKeyValue("route", routeCapability)
          .log("Scheduled extract skipped for retired route");
      return;
    }
    try {
      LocalDate businessDate = LocalDate.now(zone);
      JobParameters parameters =
          new JobParametersBuilder()
              .addString("businessDate", businessDate.toString(), true)
              .toJobParameters();
      JobExecution execution = jobLauncher.run(job, parameters);
      log.atInfo()
          .addKeyValue("event", "pas.batch.extract.scheduled")
          .addKeyValue("job", job.getName())
          .addKeyValue("jobExecutionId", execution.getId())
          .log("Scheduled extract launched");
    } catch (Exception exception) {
      log.atError()
          .addKeyValue("event", "pas.batch.extract.schedule.failed")
          .addKeyValue("job", job.getName())
          .addKeyValue("failureType", exception.getClass().getSimpleName())
          .log("Scheduled extract could not launch");
    }
  }
}
