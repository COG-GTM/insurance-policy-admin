package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
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
                    factorSet,
                    parameters.policyTypeKey(),
                    request.classCode(),
                    request.ratingDate())
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
}
