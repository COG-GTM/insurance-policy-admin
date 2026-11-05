package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ProRataTest {

  private static final LocalDate JAN_1_2023 = LocalDate.of(2023, 1, 1);
  private static final LocalDate JAN_1_2024 = LocalDate.of(2024, 1, 1);

  @Test
  void factorIsDaysRemainingOverDaysInTermTruncatedToSixDecimals() {
    ProRata p = ProRata.of(JAN_1_2023, JAN_1_2024, LocalDate.of(2023, 7, 2), null);

    assertThat(p.daysInTerm()).isEqualTo(365);
    assertThat(p.daysRemaining()).isEqualTo(183);
    assertThat(p.factor()).isEqualByComparingTo("0.501369");
    assertThat(p.adjustment()).isEqualByComparingTo("0.00");
  }

  @Test
  void adjustmentIsTheAnnualChangeTimesTheFactorTruncated() {
    ProRata p =
        ProRata.of(JAN_1_2023, JAN_1_2024, LocalDate.of(2023, 7, 2), new BigDecimal("100.00"));

    assertThat(p.adjustment()).isEqualByComparingTo("50.13");
    assertThat(p.applyTo(new BigDecimal("1000.00"))).isEqualByComparingTo("1050.13");
  }

  @Test
  void negativeChangeReducesThePremiumAndTruncatesTowardZero() {
    ProRata p =
        ProRata.of(JAN_1_2023, JAN_1_2024, LocalDate.of(2023, 7, 2), new BigDecimal("-100.00"));

    assertThat(p.adjustment()).isEqualByComparingTo("-50.13");
  }

  @Test
  void leapYearTermHas366Days() {
    ProRata p =
        ProRata.of(
            LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), LocalDate.of(2024, 3, 1), null);

    assertThat(p.daysInTerm()).isEqualTo(366);
    assertThat(p.daysRemaining()).isEqualTo(306);
    assertThat(p.factor()).isEqualByComparingTo("0.836065");
  }

  @Test
  void termStartingOnFeb29() {
    LocalDate feb29 = LocalDate.of(2024, 2, 29);
    ProRata p = ProRata.of(feb29, LocalDate.of(2025, 2, 28), feb29, new BigDecimal("365.00"));

    assertThat(p.daysInTerm()).isEqualTo(365);
    assertThat(p.factor()).isEqualByComparingTo("1.000000");
    assertThat(p.adjustment()).isEqualByComparingTo("365.00");
  }

  @Test
  void zeroLengthTermUsesAFactorOfOne() {
    ProRata p = ProRata.of(JAN_1_2024, JAN_1_2024, JAN_1_2024, new BigDecimal("10.00"));

    assertThat(p.daysInTerm()).isZero();
    assertThat(p.daysRemaining()).isZero();
    assertThat(p.factor()).isEqualByComparingTo("1.000000");
    assertThat(p.adjustment()).isEqualByComparingTo("10.00");
  }

  @Test
  void remainingDaysAreClampedToTheTerm() {
    ProRata afterExpiry =
        ProRata.of(JAN_1_2023, JAN_1_2024, LocalDate.of(2024, 2, 1), new BigDecimal("100.00"));
    ProRata beforeStart =
        ProRata.of(JAN_1_2023, JAN_1_2024, LocalDate.of(2022, 12, 1), new BigDecimal("100.00"));

    assertThat(afterExpiry.daysRemaining()).isZero();
    assertThat(afterExpiry.adjustment()).isEqualByComparingTo("0.00");
    assertThat(beforeStart.daysRemaining()).isEqualTo(365);
    assertThat(beforeStart.factor()).isEqualByComparingTo("1.000000");
  }

  @Test
  void multiYearTermsAreNotTruncatedToThreeDigits() {
    ProRata p =
        ProRata.of(
            LocalDate.of(2023, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2024, 7, 2), null);

    assertThat(p.daysInTerm()).isEqualTo(1096);
    assertThat(p.daysRemaining()).isEqualTo(548);
  }

  @Test
  void termEndingBeforeItStartsIsRejected() {
    assertThatThrownBy(() -> ProRata.of(JAN_1_2024, JAN_1_2023, JAN_1_2023, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overflowingPremiumIsRefused() {
    ProRata p = ProRata.of(JAN_1_2023, JAN_1_2024, JAN_1_2023, new BigDecimal("10.00"));

    assertThatThrownBy(() -> p.applyTo(new BigDecimal("999999999.99")))
        .isInstanceOf(RatingOverflowException.class);
  }

  @Test
  void endorsementTypeMustBeOneOfTheKnownCodes() {
    assertThat(PolicyChangeError.checkEndorsementType("CAD")).isEmpty();
    assertThat(PolicyChangeError.checkEndorsementType("CAN")).isEmpty();
    assertThat(PolicyChangeError.checkEndorsementType(null))
        .contains(PolicyChangeError.ENDORSEMENT_TYPE_REQUIRED);
    assertThat(PolicyChangeError.checkEndorsementType("   "))
        .contains(PolicyChangeError.ENDORSEMENT_TYPE_REQUIRED);
    assertThat(PolicyChangeError.checkEndorsementType("\u0000\u0000\u0000"))
        .contains(PolicyChangeError.ENDORSEMENT_TYPE_INVALID);
    assertThat(PolicyChangeError.checkEndorsementType("CAD "))
        .contains(PolicyChangeError.ENDORSEMENT_TYPE_INVALID);
    assertThat(PolicyChangeError.checkEndorsementType("XYZ"))
        .contains(PolicyChangeError.ENDORSEMENT_TYPE_INVALID);
  }
}
