package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
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
@ConditionalOnProperty(name = "pas.batch.premium.scheduler-enabled", havingValue = "true")
public class PremiumSchedulingConfiguration {
  private static final Logger log = LoggerFactory.getLogger(PremiumSchedulingConfiguration.class);
  private final JobLauncher jobLauncher;
  private final Job premiumRecalculationJob;
  private final RouteFlags routes;
  private final ZoneId zone;

  public PremiumSchedulingConfiguration(
      JobLauncher jobLauncher,
      Job premiumRecalculationJob,
      RouteFlags routes,
      @Value("${pas.batch.premium.zone:America/New_York}") String zone) {
    this.jobLauncher = jobLauncher;
    this.premiumRecalculationJob = premiumRecalculationJob;
    this.routes = routes;
    this.zone = ZoneId.of(zone);
  }

  @Scheduled(
      cron = "${pas.batch.premium.schedule:0 0 1 * * *}",
      zone = "${pas.batch.premium.zone:America/New_York}")
  public void launchPremiumRecalculation() {
    if (routes.state(RouteFlags.PREMIUM_BATCH) == RouteState.LEGACY) {
      log.atInfo()
          .addKeyValue("event", "pas.batch.premium.schedule.skipped")
          .addKeyValue("route", "legacy")
          .log("Premium batch schedule skipped by legacy route");
      return;
    }
    try {
      JobParameters parameters =
          new JobParametersBuilder()
              .addString("businessDate", LocalDate.now(zone).toString(), true)
              .toJobParameters();
      var execution = jobLauncher.run(premiumRecalculationJob, parameters);
      log.atInfo()
          .addKeyValue("event", "pas.batch.premium.scheduled")
          .addKeyValue("jobExecutionId", execution.getId())
          .log("Premium batch launched by scheduler");
    } catch (Exception exception) {
      log.atError()
          .addKeyValue("event", "pas.batch.premium.schedule.failed")
          .addKeyValue("failureType", exception.getClass().getSimpleName())
          .log("Premium batch could not be launched by scheduler");
    }
  }
}
