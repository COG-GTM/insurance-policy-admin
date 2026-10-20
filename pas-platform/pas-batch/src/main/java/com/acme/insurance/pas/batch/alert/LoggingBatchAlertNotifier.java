package com.acme.insurance.pas.batch.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default notifier: one ERROR line on the {@code pas.batch.alert} logger for log-based alerting.
 */
@Component
public class LoggingBatchAlertNotifier implements BatchAlertNotifier {

  private static final Logger ALERTS = LoggerFactory.getLogger("pas.batch.alert");

  @Override
  public void notify(BatchAlert alert) {
    ALERTS.error(
        "BATCH ALERT job={} executionId={} status={} exitCode={} parameters={} failures={}",
        alert.jobName(),
        alert.jobExecutionId(),
        alert.status(),
        alert.exitCode(),
        alert.parameters(),
        alert.failures());
  }
}
