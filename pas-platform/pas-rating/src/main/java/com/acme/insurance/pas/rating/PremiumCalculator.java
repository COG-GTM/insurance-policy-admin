package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class PremiumCalculator {
  private static final String LEGACY_FACTOR_SET = "LEGACY";

  private final RatingTableRepository repository;
  private final FactorMode factorMode;

  public PremiumCalculator(RatingTableRepository repository, FactorMode factorMode) {
    this.repository = Objects.requireNonNull(repository);
    this.factorMode = Objects.requireNonNull(factorMode);
  }

  public PremiumBreakdown calculate(RatingRequest request) {
    Objects.requireNonNull(request, "request is required");
    RatingParameters parameters =
        repository
            .findParameters(request.policyType(), request.ratingDate())
            .orElseThrow(() -> new RatingConfigurationException("Missing rating parameters"));
    String factorSet =
        factorMode == FactorMode.LEGACY
            ? LEGACY_FACTOR_SET
            : repository
                .findActiveFactorSet()
                .orElseThrow(
                    () -> new RatingConfigurationException("Missing active rating factor set"));
    BigDecimal territoryFactor =
        CobolPic.store(
            repository
                .findTerritoryFactor(factorSet, request.territoryCode(), request.ratingDate())
                .orElseThrow(
                    () ->
                        new RatingConfigurationException(
                            "Missing territory factor for the selected rating factor set")),
            3,
            4,
            "TERRITORY-FACTOR");
    BigDecimal classFactor =
        CobolPic.store(
            repository
                .findClassFactor(
                    factorSet, request.policyType(), request.classCode(), request.ratingDate())
                .orElseThrow(
                    () ->
                        new RatingConfigurationException(
                            "Missing class factor for the selected rating factor set")),
            3,
            4,
            "CLASS-FACTOR");
    BigDecimal experienceMod = CobolPic.store(parameters.experienceMod(), 3, 4, "EXPERIENCE-MOD");
    BigDecimal scheduleMod = CobolPic.store(parameters.scheduleMod(), 3, 4, "SCHEDULE-MOD");
    BigDecimal taxRate = CobolPic.store(parameters.taxRate(), 1, 4, "WS-TAX-RATE");

    BigDecimal base = CobolPic.store(parameters.basePremium(), 9, 2, "WS-BASE-PREMIUM");
    BigDecimal territoryPremium =
        CobolPic.store(base.multiply(territoryFactor), 9, 2, "WS-TERR-PREMIUM");
    BigDecimal classPremium =
        CobolPic.store(territoryPremium.multiply(classFactor), 9, 2, "WS-CLASS-PREMIUM");
    BigDecimal experiencePremium =
        CobolPic.store(classPremium.multiply(experienceMod), 9, 2, "WS-MOD-PREMIUM");
    BigDecimal ratedPremium =
        CobolPic.store(experiencePremium.multiply(scheduleMod), 9, 2, "WS-RATED-PREMIUM");
    BigDecimal taxAmount = CobolPic.store(ratedPremium.multiply(taxRate), 7, 2, "WS-TAX-AMOUNT");
    BigDecimal surcharge = CobolPic.store(parameters.surchargeAmount(), 7, 2, "WS-SURCHARGE");
    BigDecimal total =
        CobolPic.store(ratedPremium.add(taxAmount).add(surcharge), 9, 2, "WS-FINAL-PREMIUM");

    return new PremiumBreakdown(
        request.policyType(),
        parameters.policyTypeKey(),
        request.ratingDate(),
        factorMode,
        factorSet,
        parameters.effectiveDate(),
        base,
        territoryFactor,
        classFactor,
        experienceMod,
        scheduleMod,
        territoryPremium,
        classPremium,
        experiencePremium,
        ratedPremium,
        taxRate,
        taxAmount,
        surcharge,
        total);
  }

  /**
   * POLRNW 4000-CALCULATE-NEW-PREMIUM: the renewal does not re-rate; it uplifts the expiring
   * premium (Q-06, truncated) and caps the increase (Q-04). Unlike COBOL, a zero premium (division
   * by zero) and a premium that no longer fits S9(9)V99 are rejected instead of wrapping (Q-47).
   */
  public static RenewalPremium renewalPremium(
      BigDecimal oldPremium, BigDecimal upliftPct, BigDecimal capPct) {
    Objects.requireNonNull(oldPremium, "oldPremium is required");
    Objects.requireNonNull(upliftPct, "upliftPct is required");
    Objects.requireNonNull(capPct, "capPct is required");
    BigDecimal old = CobolPic.store(oldPremium, 9, 2, "WS-OLD-PREMIUM");
    if (old.signum() == 0) {
      throw new RenewalPremiumException("ZERO PREMIUM CANNOT BE RENEWED");
    }
    try {
      BigDecimal uplifted =
          CobolPic.store(old.multiply(multiplier(upliftPct)), 9, 2, "WS-NEW-PREMIUM");
      BigDecimal rateChange = rateChangePct(old, uplifted);
      boolean capped = rateChange.compareTo(capPct) > 0;
      BigDecimal renewed =
          capped
              ? CobolPic.store(old.multiply(multiplier(capPct)), 9, 2, "WS-NEW-PREMIUM")
              : uplifted;
      return new RenewalPremium(old, renewed, rateChange, capped);
    } catch (PremiumOverflowException exception) {
      throw new RenewalPremiumException("RENEWAL PREMIUM OUT OF RANGE", exception);
    }
  }

  private static BigDecimal multiplier(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.movePointLeft(2));
  }

  private static BigDecimal rateChangePct(BigDecimal old, BigDecimal renewed) {
    BigDecimal ratio = renewed.subtract(old).divide(old, 18, RoundingMode.DOWN);
    return CobolPic.store(ratio.movePointRight(2), 3, 2, "WS-RATE-CHANGE-PCT");
  }
}
