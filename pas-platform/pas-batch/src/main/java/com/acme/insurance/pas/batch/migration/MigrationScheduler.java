package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.schedule.ExtractJobLauncher;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Parallel-run schedule (one scheduling replica): delta sync every 15 minutes, reconciliation daily
 * 05:00 and the output diff daily 06:00 for the previous business date (after the 23:00 extracts
 * and the mainframe batch window). Off unless {@code pas.migration.schedule.enabled}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = {"pas.migration.enabled", "pas.migration.schedule.enabled"},
    havingValue = "true")
public class MigrationScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationScheduler.class);

  private final ExtractJobLauncher launcher;
  private final Job delta;
  private final Job reconcile;
  private final Job parallelRun;
  private final ZoneId zone;

  public MigrationScheduler(
      ExtractJobLauncher launcher,
      @Qualifier(MigrationConfiguration.DELTA_JOB) Job delta,
      @Qualifier(MigrationConfiguration.RECONCILE_JOB) Job reconcile,
      @Qualifier(MigrationConfiguration.PARALLEL_RUN_JOB) Job parallelRun,
      @Value("${pas.schedule.zone}") ZoneId zone) {
    this.launcher = launcher;
    this.delta = delta;
    this.reconcile = reconcile;
    this.parallelRun = parallelRun;
    this.zone = zone;
  }

  @Scheduled(cron = "${pas.migration.schedule.delta-cron}", zone = "${pas.schedule.zone}")
  public void deltaSync() {
    run(delta, LocalDate.now(zone));
  }

  @Scheduled(cron = "${pas.migration.schedule.reconcile-cron}", zone = "${pas.schedule.zone}")
  public void reconcile() {
    run(reconcile, LocalDate.now(zone));
  }

  @Scheduled(cron = "${pas.migration.schedule.parallel-run-cron}", zone = "${pas.schedule.zone}")
  public void parallelRun() {
    run(parallelRun, LocalDate.now(zone).minusDays(1));
  }

  private void run(Job job, LocalDate businessDate) {
    try {
      var execution = launcher.launch(job, businessDate);
      LOG.info(
          "scheduled {} businessDate={} exit={}",
          job.getName(),
          businessDate,
          execution.getExitStatus().getExitCode());
    } catch (Exception e) {
      LOG.error(
          "scheduled {} businessDate={} could not be launched", job.getName(), businessDate, e);
    }
  }
}
