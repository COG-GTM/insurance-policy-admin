package com.acme.insurance.pas.batch.schedule;

import com.acme.insurance.pas.batch.alert.BatchAlert;
import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Replaces the CA-7 schedule: PAS0100 DAILY-EXTRACT daily 23:00 and PAS0200 MONTHLY-EXPOSURE on the
 * 1st at 02:00, America/New_York. Off by default ({@code pas.schedule.enabled}) so nothing is sent
 * to Claims/Actuarial before their cut-over; run one scheduling replica.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "pas.schedule.enabled", havingValue = "true")
public class ExtractScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ExtractScheduler.class);

  private final ExtractJobLauncher launcher;
  private final Job dailyExtractJob;
  private final Job monthlyExposureJob;
  private final ZoneId zone;
  private final List<BatchAlertNotifier> notifiers;

  public ExtractScheduler(
      ExtractJobLauncher launcher,
      @Qualifier("dailyExtractJob") Job dailyExtractJob,
      @Qualifier("monthlyExposureJob") Job monthlyExposureJob,
      @Value("${pas.schedule.zone}") ZoneId zone,
      List<BatchAlertNotifier> notifiers) {
    this.launcher = launcher;
    this.dailyExtractJob = dailyExtractJob;
    this.monthlyExposureJob = monthlyExposureJob;
    this.zone = zone;
    this.notifiers = List.copyOf(notifiers);
  }

  @Scheduled(cron = "${pas.schedule.daily-extract-cron}", zone = "${pas.schedule.zone}")
  public void dailyExtract() {
    run(dailyExtractJob);
  }

  @Scheduled(cron = "${pas.schedule.monthly-exposure-cron}", zone = "${pas.schedule.zone}")
  public void monthlyExposure() {
    run(monthlyExposureJob);
  }

  private void run(Job job) {
    LocalDate businessDate = LocalDate.now(zone);
    try {
      JobExecution execution = launcher.launch(job, businessDate);
      LOG.info(
          "scheduled {} businessDate={} finished status={}",
          job.getName(),
          businessDate,
          execution.getStatus());
    } catch (Exception e) {
      // Launch failures (e.g. already running) never reach the job listener, so alert here.
      LOG.error(
          "scheduled {} businessDate={} could not be launched", job.getName(), businessDate, e);
      BatchAlert alert =
          new BatchAlert(
              job.getName(),
              -1,
              "NOT_LAUNCHED",
              "LAUNCH_FAILED",
              Map.of("businessDate", businessDate.toString()),
              List.of(e.toString()));
      for (BatchAlertNotifier notifier : notifiers) {
        try {
          notifier.notify(alert);
        } catch (RuntimeException notifierFailure) {
          LOG.error("alert notifier {} failed", notifier.getClass().getName(), notifierFailure);
        }
      }
    }
  }
}
