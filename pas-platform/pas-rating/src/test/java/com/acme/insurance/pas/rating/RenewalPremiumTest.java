package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RenewalPremiumTest {
  private static final BigDecimal UPLIFT = new BigDecimal("5.00");
  private static final BigDecimal CAP = new BigDecimal("15.00");

  @Test
  void upliftTruncatesLikeCobolCompute() {
    RenewalPremium premium =
        PremiumCalculator.renewalPremium(new BigDecimal("1234.57"), UPLIFT, CAP);
    assertThat(premium.newPremium()).isEqualByComparingTo("1296.29");
    assertThat(premium.rateChangePct()).isEqualByComparingTo("4.99");
    assertThat(premium.capped()).isFalse();
  }

  @Test
  void capAppliesWhenUpliftExceedsIt() {
    RenewalPremium premium =
        PremiumCalculator.renewalPremium(new BigDecimal("1000.00"), new BigDecimal("20"), CAP);
    assertThat(premium.newPremium()).isEqualByComparingTo("1150.00");
    assertThat(premium.capped()).isTrue();
  }

  @Test
  void capIsAppliedBeforeTheRangeCheck() {
    RenewalPremium premium =
        PremiumCalculator.renewalPremium(
            new BigDecimal("950000000.00"), new BigDecimal("20"), new BigDecimal("2"));
    assertThat(premium.newPremium()).isEqualByComparingTo("969000000.00");
    assertThat(premium.capped()).isTrue();
  }

  @Test
  void zeroAndOverflowingPremiumsAreRejected() {
    assertThatThrownBy(() -> PremiumCalculator.renewalPremium(BigDecimal.ZERO, UPLIFT, CAP))
        .isInstanceOf(RenewalPremiumException.class);
    assertThatThrownBy(
            () -> PremiumCalculator.renewalPremium(new BigDecimal("999999999.99"), UPLIFT, CAP))
        .isInstanceOf(RenewalPremiumException.class);
  }
}
