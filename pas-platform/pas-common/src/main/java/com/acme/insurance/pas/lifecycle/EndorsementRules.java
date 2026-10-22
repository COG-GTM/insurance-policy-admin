package com.acme.insurance.pas.lifecycle;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** POLEND 3000/4000/5000 rules with the AB-526 fixes. */
public final class EndorsementRules {
  /** ENDORSEMENT_SEQ is PIC 9(05) in POLEND (Q-47). */
  public static final int MAX_ENDORSEMENT_SEQ = 99_999;

  /** Q-11 (Preserve): the adjustment is 0.00; Q-49: always initialised rather than left in WS. */
  public static final BigDecimal PREMIUM_ADJUSTMENT = new BigDecimal("0.00");

  private static final BigDecimal MAX_PREMIUM = new BigDecimal("999999999.99");

  private EndorsementRules() {}

  public static void checkActive(String policyStatus) {
    if (policyStatus == null || !"AC".equals(policyStatus.trim())) {
      throw new LifecycleRejectedException(Rejection.POLICY_NOT_ACTIVE);
    }
  }

  /**
   * Q-48: the term must be non-empty and the endorsement date must fall in [effective, expiry]. The
   * expiry date itself is accepted (zero days remaining, as in POLEND).
   */
  public static void checkTerm(LocalDate effectiveDate, LocalDate expiryDate, LocalDate date) {
    if (effectiveDate == null || expiryDate == null || expiryDate.isBefore(effectiveDate)) {
      throw new LifecycleRejectedException(Rejection.INVALID_TERM);
    }
    if (expiryDate.isEqual(effectiveDate)) {
      throw new LifecycleRejectedException(Rejection.ZERO_LENGTH_TERM);
    }
    if (date == null || date.isBefore(effectiveDate) || date.isAfter(expiryDate)) {
      throw new LifecycleRejectedException(Rejection.OUTSIDE_TERM);
    }
  }

  /**
   * Days remaining / days in term, truncated to the six decimals of WS-PRORATA-FACTOR S9V9(6). Java
   * counts real days; COBOL stores both counts in unsigned PIC 9(03), so terms over 999 days and
   * dates after expiry differ (they are rejected by {@link #checkTerm} first). Q-11 keeps the
   * factor unused.
   */
  public static BigDecimal proRataFactor(
      LocalDate effectiveDate, LocalDate expiryDate, LocalDate date) {
    checkTerm(effectiveDate, expiryDate, date);
    long daysInTerm = ChronoUnit.DAYS.between(effectiveDate, expiryDate);
    long daysRemaining = ChronoUnit.DAYS.between(date, expiryDate);
    return BigDecimal.valueOf(daysRemaining)
        .divide(BigDecimal.valueOf(daysInTerm), 6, RoundingMode.DOWN);
  }

  /** MAX(ENDORSEMENT_SEQ) + 1, rejected instead of wrapping 99999 to 00000 (Q-47). */
  public static int nextSequence(Integer maxSequence) {
    int max = maxSequence == null ? 0 : maxSequence;
    if (max < 0 || max >= MAX_ENDORSEMENT_SEQ) {
      throw new LifecycleRejectedException(Rejection.ENDORSEMENT_SEQUENCE_LIMIT);
    }
    return max + 1;
  }

  /** POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST without wrapping S9(9)V99 (Q-47). */
  public static BigDecimal adjustedPremium(BigDecimal totalPremium, BigDecimal adjustment) {
    BigDecimal current = totalPremium == null ? BigDecimal.ZERO : totalPremium;
    BigDecimal adjusted = current.add(adjustment).setScale(2, RoundingMode.DOWN);
    if (adjusted.abs().compareTo(MAX_PREMIUM) > 0) {
      throw new LifecycleRejectedException(Rejection.PREMIUM_OUT_OF_RANGE);
    }
    return adjusted;
  }
}
