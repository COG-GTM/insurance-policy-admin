package com.acme.insurance.pas.lifecycle;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.IntSupplier;

/** POLRNW 3000 eligibility and 6000 term rules with the AB-526 fixes. */
public final class RenewalRules {
  /** POLICY-RENEWAL-COUNT is PIC 9(03) in the legacy record (Q-18). */
  public static final int MAX_RENEWAL_COUNT = 999;

  private RenewalRules() {}

  /**
   * Checks eligibility. Open claims are only counted for otherwise eligible policies; a failing
   * claims source propagates so the caller fails closed (Q-02).
   */
  public static void checkEligibility(
      String policyStatus,
      String uwStatus,
      LocalDate expiryDate,
      int renewalCount,
      LocalDate today,
      RenewalSettings settings,
      IntSupplier openClaims) {
    Objects.requireNonNull(settings, "settings are required");
    String status = trim(policyStatus);
    if (!"AC".equals(status) && !"EX".equals(status)) {
      throw new LifecycleRejectedException(Rejection.POLICY_NOT_ELIGIBLE_FOR_RENEWAL);
    }
    if (expiryDate == null) {
      throw new LifecycleRejectedException(Rejection.POLICY_NOT_ELIGIBLE_FOR_RENEWAL);
    }
    if ("DC".equals(trim(uwStatus))) {
      throw new LifecycleRejectedException(Rejection.UNDERWRITING_DECLINED);
    }
    if (settings.windowEnabled()
        && (today.isBefore(expiryDate.minusDays(settings.daysBeforeExpiry()))
            || today.isAfter(expiryDate.plusDays(settings.daysAfterExpiry())))) {
      throw new LifecycleRejectedException(Rejection.OUTSIDE_RENEWAL_WINDOW);
    }
    nextRenewalCount(renewalCount);
    if (openClaims.getAsInt() > settings.maxOpenClaims()) {
      throw new LifecycleRejectedException(Rejection.TOO_MANY_OPEN_CLAIMS);
    }
  }

  /** Q-18: reject instead of wrapping 999 to 000. */
  public static int nextRenewalCount(int renewalCount) {
    if (renewalCount < 0 || renewalCount >= MAX_RENEWAL_COUNT) {
      throw new LifecycleRejectedException(Rejection.RENEWAL_COUNT_LIMIT);
    }
    return renewalCount + 1;
  }

  /** The new term starts on the old expiry date (POLRNW 6000). */
  public static LocalDate renewalEffectiveDate(LocalDate expiryDate) {
    return expiryDate;
  }

  /** Q-01: calendar arithmetic instead of YYYYMMDD + 10000; 29 Feb becomes 28 Feb. */
  public static LocalDate renewalExpiryDate(LocalDate expiryDate) {
    return expiryDate.plusYears(1);
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
