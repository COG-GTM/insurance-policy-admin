package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.PremiumOverflowException;
import com.acme.insurance.pas.rating.RatingRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.batch.item.ItemProcessor;

public class PremiumProcessor implements ItemProcessor<PremiumPolicy, PremiumRow> {
  private final PremiumCalculator calculator;
  private final LocalDate businessDate;

  public PremiumProcessor(PremiumCalculator calculator, LocalDate businessDate) {
    this.calculator = calculator;
    this.businessDate = businessDate;
  }

  @Override
  public PremiumRow process(PremiumPolicy policy) {
    try {
      PremiumBreakdown breakdown =
          calculator.calculate(new RatingRequest(policy.policyType(), businessDate, null, null));
      return new PremiumRow(
          policy.policyNumber(),
          1,
          policy.effectiveDate(),
          policy.expiryDate(),
          breakdown.premiumRecordBaseRate(),
          breakdown.territoryFactor(),
          breakdown.classFactor(),
          breakdown.experienceMod(),
          breakdown.scheduleMod(),
          BigDecimal.ZERO,
          breakdown.surchargeAmount(),
          breakdown.taxAmount(),
          breakdown.totalPremium(),
          "AN",
          BigDecimal.ZERO,
          businessDate,
          "PREMBAT");
    } catch (PremiumOverflowException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new PolicyRatingException("Invalid premium source row", exception);
    }
  }
}
