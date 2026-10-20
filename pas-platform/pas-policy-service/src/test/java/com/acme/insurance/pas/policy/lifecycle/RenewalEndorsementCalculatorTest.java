package com.acme.insurance.pas.policy.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RenewalEndorsementCalculatorTest {

  private static final BigDecimal UPLIFT = BigDecimal.valueOf(5);
  private static final BigDecimal CAP = BigDecimal.valueOf(15);

  @ParameterizedTest(name = "{0} renews to {1}")
  @CsvSource({
    "20240229,20250228", // Q-01 fix: leap day -> Feb 28
    "20230228,20240228",
    "20231231,20241231",
    "20250630,20260630",
    "20280229,20290228",
    "99991231,1231" // Q-07: PIC 9(8) wrap preserved
  })
  void newExpiryIsOneCalendarYearLater(int expiry, int expected) {
    assertThat(RenewalCalculator.oneYearLater(expiry)).isEqualTo(expected);
  }

  @Test
  void feb29TermRenewsIntoAValidDate() {
    RenewalCalculator.RenewalTerms t =
        RenewalCalculator.calculate(new BigDecimal("1000.00"), UPLIFT, CAP, 20240229, 0);
    assertThat(t.newEffectiveDate()).isEqualTo(20240229);
    assertThat(t.newExpiryDate()).isEqualTo(20250228);
    assertThat(YyyyMmDd.toDate(t.newExpiryDate())).hasToString("2025-02-28");
  }

  @Test
  void premiumTruncatesAndCapAppliesOnlyAboveTheCap() {
    RenewalCalculator.RenewalTerms t =
        RenewalCalculator.calculate(new BigDecimal("1234.57"), UPLIFT, CAP, 20250630, 0);
    assertThat(t.newPremium()).isEqualByComparingTo("1296.29");
    assertThat(t.rateChangePct()).isEqualByComparingTo("4.99");
    assertThat(t.capApplied()).isFalse();

    RenewalCalculator.RenewalTerms capped =
        RenewalCalculator.calculate(
            new BigDecimal("1000.00"), BigDecimal.valueOf(20), CAP, 20250630, 0);
    assertThat(capped.capApplied()).isTrue();
    assertThat(capped.newPremium()).isEqualByComparingTo("1150.00");
    assertThat(capped.rateChangePct()).isEqualByComparingTo("20.00");
  }

  @Test
  void zeroPremiumAndRenewalCountWrap() {
    RenewalCalculator.RenewalTerms t =
        RenewalCalculator.calculate(BigDecimal.ZERO, UPLIFT, CAP, 20250630, 999);
    assertThat(t.rateChangePct()).isEqualByComparingTo("0.00");
    assertThat(t.newPremium()).isEqualByComparingTo("0.00");
    assertThat(t.newRenewalCount()).isZero();
  }

  @Test
  void eligibility() {
    assertThat(RenewalService.eligibilityMessage("AC", 3, 3)).isEmpty();
    assertThat(RenewalService.eligibilityMessage("EX", 0, 3)).isEmpty();
    assertThat(RenewalService.eligibilityMessage("AC", 4, 3))
        .contains(RenewalService.MSG_OPEN_CLAIMS);
    assertThat(RenewalService.eligibilityMessage("CN", 9, 3))
        .contains(RenewalService.MSG_NOT_ELIGIBLE);
  }

  @Test
  void zeroLengthTermUsesFactorOne() {
    EndorsementCalculator.ProRata p = EndorsementCalculator.proRata(20250101, 20250101, 20250101);
    assertThat(p.daysInTerm()).isZero();
    assertThat(p.daysRemaining()).isZero();
    assertThat(p.factor()).isEqualByComparingTo("1.000000");
    assertThat(EndorsementCalculator.premiumAdjustment(p)).isEqualByComparingTo("0.00");
  }

  @Test
  void proRataOverDaysRemainingIncludingFeb29() {
    EndorsementCalculator.ProRata leap =
        EndorsementCalculator.proRata(20240101, 20250101, 20240229);
    assertThat(leap.daysInTerm()).isEqualTo(366);
    assertThat(leap.daysRemaining()).isEqualTo(307);
    assertThat(leap.factor()).isEqualByComparingTo("0.838797");

    EndorsementCalculator.ProRata feb29Term =
        EndorsementCalculator.proRata(20240229, 20250228, 20240829);
    assertThat(feb29Term.daysInTerm()).isEqualTo(365);
    assertThat(feb29Term.daysRemaining()).isEqualTo(183);
    assertThat(feb29Term.factor()).isEqualByComparingTo("0.501369");
  }

  @Test
  void endorsementValidationLastCheckWins() {
    assertThat(EndorsementService.validationMessage("AC", "CAD")).isEmpty();
    assertThat(EndorsementService.validationMessage("PN", "CAD"))
        .contains(EndorsementService.MSG_NOT_ACTIVE);
    assertThat(EndorsementService.validationMessage("PN", " "))
        .isEqualTo(Optional.of(EndorsementService.MSG_TYPE_REQUIRED));
    assertThat(EndorsementCalculator.nextSequence(99999)).isZero();
  }

  @Test
  void integerOfDateMatchesCobol() {
    assertThat(YyyyMmDd.integerOfDate(16010101)).isEqualTo(1);
    assertThat(YyyyMmDd.integerOfDate(20250229)).isZero();
    assertThat(YyyyMmDd.integerOfDate(1231)).isZero();
    assertThatThrownBy(() -> YyyyMmDd.toDate(20250229)).isInstanceOf(DateTimeException.class);
  }
}
