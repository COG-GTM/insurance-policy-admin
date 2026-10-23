package com.acme.insurance.pas.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EndorsementRulesTest {
  private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 1);
  private static final LocalDate EXPIRY = LocalDate.of(2025, 1, 1);

  @Test
  void proRataOverDaysRemainingTruncatedToSixDecimals() {
    assertThat(EndorsementRules.proRataFactor(EFFECTIVE, EXPIRY, LocalDate.of(2024, 7, 1)))
        .isEqualByComparingTo("0.502732");
  }

  @Test
  void firstAndLastDayOfTermAreInsideTheTerm() {
    assertThat(EndorsementRules.proRataFactor(EFFECTIVE, EXPIRY, EFFECTIVE))
        .isEqualByComparingTo("1.000000");
    assertThat(EndorsementRules.proRataFactor(EFFECTIVE, EXPIRY, EXPIRY))
        .isEqualByComparingTo("0.000000");
  }

  @Test
  void feb29TermCountsTheLeapDay() {
    LocalDate effective = LocalDate.of(2024, 2, 29);
    LocalDate expiry = LocalDate.of(2025, 2, 28);
    assertThat(EndorsementRules.proRataFactor(effective, expiry, LocalDate.of(2024, 3, 1)))
        .isEqualByComparingTo("0.997260");
  }

  @Test
  void zeroLengthInvertedAndOutOfTermAreRejected() {
    assertRejected(
        () -> EndorsementRules.checkTerm(EXPIRY, EXPIRY, EXPIRY), Rejection.ZERO_LENGTH_TERM);
    assertRejected(
        () -> EndorsementRules.checkTerm(EXPIRY, EFFECTIVE, EXPIRY), Rejection.INVALID_TERM);
    assertRejected(
        () -> EndorsementRules.checkTerm(EFFECTIVE, EXPIRY, EXPIRY.plusDays(1)),
        Rejection.OUTSIDE_TERM);
    assertRejected(
        () -> EndorsementRules.checkTerm(EFFECTIVE, EXPIRY, EFFECTIVE.minusDays(1)),
        Rejection.OUTSIDE_TERM);
  }

  @Test
  void typeIsRequiredAndValidated() {
    assertThat(EndorsementType.fromCode(" lch ")).isEqualTo(EndorsementType.LIMIT_CHANGE);
    assertRejected(() -> EndorsementType.fromCode(" "), Rejection.ENDORSEMENT_TYPE_REQUIRED);
    assertRejected(() -> EndorsementType.fromCode("COV"), Rejection.ENDORSEMENT_TYPE_INVALID);
  }

  @Test
  void sequenceAndPremiumDoNotWrap() {
    assertThat(EndorsementRules.nextSequence(null)).isEqualTo(1);
    assertRejected(
        () -> EndorsementRules.nextSequence(99_999), Rejection.ENDORSEMENT_SEQUENCE_LIMIT);
    assertThat(
            EndorsementRules.adjustedPremium(
                new java.math.BigDecimal("1200.00"), EndorsementRules.PREMIUM_ADJUSTMENT))
        .isEqualByComparingTo("1200.00");
  }

  private static void assertRejected(Runnable action, Rejection rejection) {
    assertThatThrownBy(action::run)
        .isInstanceOf(LifecycleRejectedException.class)
        .extracting(e -> ((LifecycleRejectedException) e).rejection())
        .isEqualTo(rejection);
  }
}
