package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RenewalCalculatorTest {

  private final RenewalCalculator legacy =
      new RenewalCalculator(
          new RenewalRules(
              new BigDecimal("5.00"), new BigDecimal("15.00"), RenewalRules.LeapDayExpiry.FEB_28));

  @Test
  void standardRenewalAddsFivePercentAndStartsTheTermAtTheOldExpiry() {
    RenewalQuote quote = legacy.quote(new BigDecimal("1000.00"), LocalDate.of(2024, 6, 1), 2);

    assertThat(quote.previousPremium()).isEqualByComparingTo("1000.00");
    assertThat(quote.newPremium()).isEqualByComparingTo("1050.00");
    assertThat(quote.rateChangePct()).isEqualByComparingTo("5.00");
    assertThat(quote.capApplied()).isFalse();
    assertThat(quote.effectiveDate()).isEqualTo(LocalDate.of(2024, 6, 1));
    assertThat(quote.expiryDate()).isEqualTo(LocalDate.of(2025, 6, 1));
    assertThat(quote.renewalCount()).isEqualTo(3);
  }

  @ParameterizedTest
  @CsvSource({"1234.57, 1296.29", "0.01, 0.01", "0.19, 0.19", "999.99, 1049.98"})
  void premiumIsTruncatedToTheCentNotRounded(String old, String expected) {
    assertThat(legacy.quote(new BigDecimal(old), LocalDate.of(2024, 6, 1), 0).newPremium())
        .isEqualByComparingTo(expected);
  }

  @Test
  void upliftAboveTheCapIsCappedAtTheCap() {
    RenewalCalculator steep =
        new RenewalCalculator(
            new RenewalRules(
                new BigDecimal("20.00"),
                new BigDecimal("15.00"),
                RenewalRules.LeapDayExpiry.FEB_28));

    RenewalQuote quote = steep.quote(new BigDecimal("1000.00"), LocalDate.of(2024, 6, 1), 0);

    assertThat(quote.capApplied()).isTrue();
    assertThat(quote.rateChangePct()).isEqualByComparingTo("20.00");
    assertThat(quote.newPremium()).isEqualByComparingTo("1150.00");
  }

  @Test
  void capIsConfigurableForStateSpecificCaps() {
    RenewalCalculator tight =
        new RenewalCalculator(
            new RenewalRules(
                new BigDecimal("5.00"), new BigDecimal("3.00"), RenewalRules.LeapDayExpiry.FEB_28));

    assertThat(tight.quote(new BigDecimal("1000.00"), LocalDate.of(2024, 6, 1), 0).newPremium())
        .isEqualByComparingTo("1030.00");
  }

  @Test
  void capIsComparedOnTheCentAmountNotTheTruncatedPercentage() {
    RenewalCalculator capped =
        new RenewalCalculator(
            new RenewalRules(
                new BigDecimal("5.00"), new BigDecimal("4.99"), RenewalRules.LeapDayExpiry.FEB_28));

    RenewalQuote quote = capped.quote(new BigDecimal("100.01"), LocalDate.of(2024, 6, 1), 0);

    assertThat(quote.newPremium()).isEqualByComparingTo("105.00");
    assertThat(quote.capApplied()).isTrue();
  }

  @Test
  void zeroPremiumRenewsAtZeroInsteadOfDividingByZero() {
    RenewalQuote quote = legacy.quote(BigDecimal.ZERO, LocalDate.of(2024, 6, 1), null);

    assertThat(quote.newPremium()).isEqualByComparingTo("0.00");
    assertThat(quote.rateChangePct()).isEqualByComparingTo("0.00");
    assertThat(quote.capApplied()).isFalse();
    assertThat(quote.renewalCount()).isEqualTo(1);
  }

  @Test
  void nullPremiumIsTheColumnDefaultZero() {
    assertThat(legacy.quote(null, LocalDate.of(2024, 6, 1), 0).newPremium())
        .isEqualByComparingTo("0.00");
  }

  @Test
  void feb29ExpiryRenewsToAValidDate() {
    LocalDate feb29 = LocalDate.of(2024, 2, 29);
    RenewalCalculator mar01 =
        new RenewalCalculator(
            new RenewalRules(
                new BigDecimal("5.00"),
                new BigDecimal("15.00"),
                RenewalRules.LeapDayExpiry.MAR_01));

    assertThat(legacy.renewalExpiry(feb29)).isEqualTo(LocalDate.of(2025, 2, 28));
    assertThat(mar01.renewalExpiry(feb29)).isEqualTo(LocalDate.of(2025, 3, 1));
    assertThat(legacy.quote(new BigDecimal("100.00"), feb29, 0).effectiveDate()).isEqualTo(feb29);
  }

  @ParameterizedTest
  @CsvSource({
    "2023-02-28, 2024-02-28",
    "2023-03-01, 2024-03-01",
    "2024-02-28, 2025-02-28",
    "2024-12-31, 2025-12-31",
    "2027-02-28, 2028-02-28"
  })
  void otherDatesKeepMonthAndDayIncludingIntoALeapYear(LocalDate expiry, LocalDate renewed) {
    assertThat(legacy.renewalExpiry(expiry)).isEqualTo(renewed);
  }

  @Test
  void premiumOrRenewalCountOverflowIsRefused() {
    assertThatThrownBy(
            () -> legacy.quote(new BigDecimal("999999999.99"), LocalDate.of(2024, 6, 1), 0))
        .isInstanceOf(RatingOverflowException.class);
    assertThatThrownBy(
            () -> legacy.quote(BigDecimal.TEN, LocalDate.of(2024, 6, 1), (int) Short.MAX_VALUE))
        .isInstanceOf(RatingOverflowException.class);
  }

  @Test
  void rulesRejectNegativeOrOverlongPercentages() {
    assertThatThrownBy(
            () ->
                new RenewalRules(
                    new BigDecimal("-1"), BigDecimal.TEN, RenewalRules.LeapDayExpiry.FEB_28))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RenewalRules(
                    BigDecimal.ONE, new BigDecimal("1000.00"), RenewalRules.LeapDayExpiry.FEB_28))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RenewalRules(
                    new BigDecimal("5.001"), BigDecimal.TEN, RenewalRules.LeapDayExpiry.FEB_28))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
