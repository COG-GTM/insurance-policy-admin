package com.acme.insurance.pas.batch.premium;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default notifier: an ERROR log line (picked up by log-based alerting) and the {@code
 * pas_batch_premium_alerts_total{outcome}} counter (Prometheus alert rule in the runbook).
 */
public class LogAndMetricPremiumRunNotifier implements PremiumRunNotifier {

  public static final String ALERTS_METRIC = "pas.batch.premium.alerts";

  private static final Logger log = LoggerFactory.getLogger(LogAndMetricPremiumRunNotifier.class);

  private final Counter failed;
  private final Counter completedWithErrors;
  private final Counter launchFailed;

  public LogAndMetricPremiumRunNotifier(MeterRegistry meters) {
    this.failed = meters.counter(ALERTS_METRIC, "outcome", PremiumRunOutcome.FAILED.name());
    this.completedWithErrors =
        meters.counter(ALERTS_METRIC, "outcome", PremiumRunOutcome.COMPLETED_WITH_ERRORS.name());
    this.launchFailed = meters.counter(ALERTS_METRIC, "outcome", "LAUNCH_FAILED");
  }

  @Override
  public void runNeedsAttention(PremiumRunSummary s) {
    (s.outcome() == PremiumRunOutcome.FAILED ? failed : completedWithErrors).increment();
    log.error(
        "PREMIUM RUN NEEDS ATTENTION outcome={} runDate={} jobExecutionId={} read={} updated={}"
            + " errors={} report={} failure={}",
        s.outcome(),
        s.runDate(),
        s.jobExecutionId(),
        s.totals().read(),
        s.totals().updated(),
        s.totals().errors(),
        s.reportPath(),
        s.failureMessage());
  }

  @Override
  public void launchFailed(LocalDate runDate, Exception cause) {
    launchFailed.increment();
    log.error("PREMIUM RUN COULD NOT BE LAUNCHED runDate={}", runDate, cause);
  }
}
