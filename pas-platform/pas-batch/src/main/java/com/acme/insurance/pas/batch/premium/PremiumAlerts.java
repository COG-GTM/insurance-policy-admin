package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.alert.BatchAlert;
import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Premium-run alerts on the shared {@code batch.alert} notifiers (AB-527), replacing PREMIUM-BATCH
 * STEP030's TSO SEND to PASADMIN (Q-26). FAILED runs are raised by {@code JobFailureAlertListener};
 * this adds the two cases it does not see: a run that completed with PREMIUMS write errors, and a
 * scheduled/CLI launch that never started.
 */
final class PremiumAlerts {

  static final String COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";
  static final String LAUNCH_FAILED = "LAUNCH_FAILED";

  private static final Logger log = LoggerFactory.getLogger(PremiumAlerts.class);

  private PremiumAlerts() {}

  static BatchAlert completedWithErrors(PremiumRunSummary s) {
    return new BatchAlert(
        PremiumRecalculationJobConfig.JOB_NAME,
        s.jobExecutionId(),
        s.batchStatus(),
        COMPLETED_WITH_ERRORS,
        Map.of(PremiumRecalculationJobConfig.RUN_DATE, s.runDate().toString()),
        List.of(
            s.totals().errors()
                + " of "
                + s.totals().read()
                + " PREMIUMS writes failed; see report "
                + s.reportPath()));
  }

  static BatchAlert launchFailed(LocalDate runDate, Exception cause) {
    return new BatchAlert(
        PremiumRecalculationJobConfig.JOB_NAME,
        -1,
        "NOT_LAUNCHED",
        LAUNCH_FAILED,
        Map.of(PremiumRecalculationJobConfig.RUN_DATE, String.valueOf(runDate)),
        List.of(cause.toString()));
  }

  static void send(List<BatchAlertNotifier> notifiers, BatchAlert alert) {
    for (BatchAlertNotifier notifier : notifiers) {
      try {
        notifier.notify(alert);
      } catch (RuntimeException e) {
        log.error("alert notifier {} failed", notifier.getClass().getName(), e);
      }
    }
  }
}
