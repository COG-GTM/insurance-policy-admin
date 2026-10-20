package com.acme.insurance.pas.batch.alert;

/**
 * Pluggable alert sink (PagerDuty, Slack, email, ...). Every {@code BatchAlertNotifier} bean is
 * called on a failed extract/exposure job; {@link LoggingBatchAlertNotifier} is always present.
 */
@FunctionalInterface
public interface BatchAlertNotifier {

  void notify(BatchAlert alert);
}
