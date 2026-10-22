package com.acme.insurance.pas.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RenewalRulesTest {
  private static final LocalDate EXPIRY = LocalDate.of(2025, 1, 1);
  private static final RenewalSettings WINDOWED =
      new RenewalSettings(
          new BigDecimal("5.00"), new BigDecimal("15.00"), Map.of("ny", BigDecimal.TEN), 3, 90, 30);

  @Test
  void feb29ExpiryRollsToFeb28NotAnInvalidDate() {
    assertThat(RenewalRules.renewalExpiryDate(LocalDate.of(2024, 2, 29)))
        .isEqualTo(LocalDate.of(2025, 2, 28));
    assertThat(RenewalRules.renewalEffectiveDate(LocalDate.of(2024, 2, 29)))
        .isEqualTo(LocalDate.of(2024, 2, 29));
  }

  @Test
  void openClaimsAboveThresholdBlockRenewal() {
    assertThatThrownBy(() -> check("AC", "AP", EXPIRY, 4))
        .extracting(e -> ((LifecycleRejectedException) e).rejection())
        .isEqualTo(Rejection.TOO_MANY_OPEN_CLAIMS);
    check("AC", "AP", EXPIRY, 3);
  }

  @Test
  void claimsSourceFailurePropagatesSoCallerFailsClosed() {
    assertThatThrownBy(
            () ->
                RenewalRules.checkEligibility(
                    "AC",
                    "AP",
                    EXPIRY,
                    1,
                    EXPIRY,
                    WINDOWED,
                    () -> {
                      throw new IllegalStateException("claims down");
                    }))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void statusUnderwritingAndWindowRules() {
    check("EX", "AP", EXPIRY, 0);
    assertRejected(() -> check("CN", "AP", EXPIRY, 0), Rejection.POLICY_NOT_ELIGIBLE_FOR_RENEWAL);
    assertRejected(() -> check("AC", "DC", EXPIRY, 0), Rejection.UNDERWRITING_DECLINED);
    assertRejected(
        () -> check("AC", "AP", EXPIRY.plusDays(91), 0), Rejection.OUTSIDE_RENEWAL_WINDOW);
    assertRejected(
        () -> check("AC", "AP", EXPIRY.minusDays(31), 0), Rejection.OUTSIDE_RENEWAL_WINDOW);
    check("AC", "AP", EXPIRY.plusDays(90), 0);
  }

  @Test
  void renewalCountRejectsInsteadOfWrapping() {
    assertThat(RenewalRules.nextRenewalCount(998)).isEqualTo(999);
    assertRejected(() -> RenewalRules.nextRenewalCount(999), Rejection.RENEWAL_COUNT_LIMIT);
  }

  @Test
  void capDefaultsAndPerStateOverride() {
    assertThat(WINDOWED.capFor(null)).isEqualByComparingTo("15.00");
    assertThat(WINDOWED.capFor("NY")).isEqualByComparingTo("10");
    assertThat(WINDOWED.capFor("CA")).isEqualByComparingTo("15.00");
  }

  private static void check(String status, String uw, LocalDate expiry, int openClaims) {
    RenewalRules.checkEligibility(status, uw, expiry, 1, EXPIRY, WINDOWED, () -> openClaims);
  }

  private static void assertRejected(Runnable action, Rejection rejection) {
    assertThatThrownBy(action::run)
        .isInstanceOf(LifecycleRejectedException.class)
        .extracting(e -> ((LifecycleRejectedException) e).rejection())
        .isEqualTo(rejection);
  }
}
