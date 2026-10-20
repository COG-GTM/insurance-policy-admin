package com.acme.insurance.pas.batch.premium;

import java.time.LocalDate;

/**
 * Failure alerting for the premium run; replaces PREMIUM-BATCH STEP030's TSO SEND to PASADMIN
 * (quirk Q-26: it fired for RC 0-4 and never on failure). Every bean implementing this is called
 * when a run fails or completes with errors; add a bean to route alerts elsewhere (pager, chat,
 * e-mail). Implementations must not throw.
 */
public interface PremiumRunNotifier {

  /**
   * A run ended {@link PremiumRunOutcome#FAILED} or {@link
   * PremiumRunOutcome#COMPLETED_WITH_ERRORS}.
   */
  void runNeedsAttention(PremiumRunSummary summary);

  /** A scheduled or CLI launch could not start a run at all (no job execution exists). */
  void launchFailed(LocalDate runDate, Exception cause);
}
