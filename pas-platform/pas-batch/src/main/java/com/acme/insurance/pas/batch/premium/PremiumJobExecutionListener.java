package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import com.acme.insurance.pas.rating.RatingSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.explore.JobExplorer;

/**
 * Loads the rating tables once per run and, when the run ends, records the run summary (table + log
 * + metrics) and alerts the shared batch.alert notifiers when the run completed with PREMIUMS write
 * errors (FAILED runs are alerted by JobFailureAlertListener).
 */
public class PremiumJobExecutionListener implements JobExecutionListener {

  static final String RATING_VERSION_KEY = "premium.ratingVersion";
  static final String REPORT_PATH_KEY = "premium.reportPath";

  private static final Logger log = LoggerFactory.getLogger(PremiumJobExecutionListener.class);

  private final RatingSnapshotRegistry snapshots;
  private final JobExplorer explorer;
  private final PremiumRunSummaryRepository summaries;
  private final List<BatchAlertNotifier> notifiers;
  private final Map<PremiumRunOutcome, Counter> runs = new EnumMap<>(PremiumRunOutcome.class);
  private final Map<PremiumRunOutcome, Timer> durations = new EnumMap<>(PremiumRunOutcome.class);
  private final AtomicLong lastRead = new AtomicLong();
  private final AtomicLong lastUpdated = new AtomicLong();
  private final AtomicLong lastErrors = new AtomicLong();

  public PremiumJobExecutionListener(
      RatingSnapshotRegistry snapshots,
      JobExplorer explorer,
      PremiumRunSummaryRepository summaries,
      List<BatchAlertNotifier> notifiers,
      MeterRegistry meters) {
    this.snapshots = snapshots;
    this.explorer = explorer;
    this.summaries = summaries;
    this.notifiers = List.copyOf(notifiers);
    for (PremiumRunOutcome outcome : PremiumRunOutcome.values()) {
      runs.put(outcome, meters.counter("pas.batch.premium.runs", "outcome", outcome.name()));
      durations.put(
          outcome,
          Timer.builder("pas.batch.premium.run.duration")
              .tag("outcome", outcome.name())
              .register(meters));
    }
    meters.gauge("pas.batch.premium.last.run.policies", List.of(tag("read")), lastRead);
    meters.gauge("pas.batch.premium.last.run.policies", List.of(tag("updated")), lastUpdated);
    meters.gauge("pas.batch.premium.last.run.policies", List.of(tag("errors")), lastErrors);
  }

  private static io.micrometer.core.instrument.Tag tag(String result) {
    return io.micrometer.core.instrument.Tag.of("result", result);
  }

  @Override
  public void beforeJob(JobExecution execution) {
    LocalDate runDate = runDate(execution);
    RatingSnapshot snapshot = snapshots.load(execution.getId(), runDate);
    String version = snapshot.parameters().rateVersion() + "/" + snapshot.factorSet();
    execution.getExecutionContext().putString(RATING_VERSION_KEY, version);
    log.info(
        "Premium run starting runDate={} jobExecutionId={} rating={}",
        runDate,
        execution.getId(),
        version);
  }

  @Override
  public void afterJob(JobExecution execution) {
    snapshots.release(execution.getId());
    PremiumRunTotals totals = PremiumRunTotals.of(explorer, execution.getJobInstance());
    if (execution.getStatus() == BatchStatus.COMPLETED && totals.errors() > 0) {
      execution.setExitStatus(PremiumRunOutcome.COMPLETED_WITH_ERRORS_STATUS);
    }
    PremiumRunOutcome outcome = PremiumRunOutcome.of(execution);
    PremiumRunSummary summary =
        new PremiumRunSummary(
            execution.getId(),
            execution.getJobInstance().getInstanceId(),
            runDate(execution),
            execution.getStatus().name(),
            execution.getExitStatus().getExitCode(),
            outcome,
            totals,
            contextString(execution, RATING_VERSION_KEY),
            contextString(execution, REPORT_PATH_KEY),
            execution.getStartTime(),
            execution.getEndTime(),
            firstFailure(execution));
    try {
      summaries.save(summary);
    } catch (RuntimeException e) {
      log.error("Could not record premium run summary {}", summary, e);
    }
    recordMetrics(execution, outcome, totals);
    log.info(
        "Premium run finished runDate={} jobExecutionId={} outcome={} read={} updated={} errors={}"
            + " report={}",
        summary.runDate(),
        summary.jobExecutionId(),
        outcome,
        totals.read(),
        totals.updated(),
        totals.errors(),
        summary.reportPath());
    if (outcome == PremiumRunOutcome.COMPLETED_WITH_ERRORS) {
      PremiumAlerts.send(notifiers, PremiumAlerts.completedWithErrors(summary));
    }
  }

  private void recordMetrics(
      JobExecution execution, PremiumRunOutcome outcome, PremiumRunTotals totals) {
    runs.get(outcome).increment();
    lastRead.set(totals.read());
    lastUpdated.set(totals.updated());
    lastErrors.set(totals.errors());
    if (execution.getStartTime() != null && execution.getEndTime() != null) {
      durations
          .get(outcome)
          .record(Duration.between(execution.getStartTime(), execution.getEndTime()));
    }
  }

  static LocalDate runDate(JobExecution execution) {
    LocalDate runDate =
        execution.getJobParameters().getLocalDate(PremiumRecalculationJobConfig.RUN_DATE);
    if (runDate == null) {
      throw new IllegalArgumentException(
          "premiumRecalculationJob needs the '"
              + PremiumRecalculationJobConfig.RUN_DATE
              + "' parameter");
    }
    return runDate;
  }

  private static String contextString(JobExecution execution, String key) {
    var ctx = execution.getExecutionContext();
    return ctx.containsKey(key) ? ctx.getString(key) : null;
  }

  private static String firstFailure(JobExecution execution) {
    List<Throwable> failures = execution.getAllFailureExceptions();
    if (failures.isEmpty()) {
      return null;
    }
    Throwable t = failures.get(0);
    String message = t.getClass().getSimpleName() + ": " + t.getMessage();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
