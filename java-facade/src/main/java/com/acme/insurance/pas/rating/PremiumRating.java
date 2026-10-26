package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Immutable result of PREMBAT 3200-CALCULATE-PREMIUM.
 */
public final class PremiumRating {

    private final String policyType;
    private final BigDecimal basePremium;
    private final BigDecimal territoryFactor;
    private final BigDecimal classFactor;
    private final BigDecimal experienceMod;
    private final BigDecimal territoryPremium;
    private final BigDecimal classPremium;
    private final BigDecimal modifiedPremium;
    private final BigDecimal taxRate;
    private final BigDecimal taxAmount;
    private final BigDecimal surcharge;
    private final BigDecimal finalPremium;

    PremiumRating(String policyType, BigDecimal basePremium,
            BigDecimal territoryFactor, BigDecimal classFactor,
            BigDecimal experienceMod, BigDecimal territoryPremium,
            BigDecimal classPremium, BigDecimal modifiedPremium,
            BigDecimal taxRate, BigDecimal taxAmount, BigDecimal surcharge,
            BigDecimal finalPremium) {
        this.policyType = policyType;
        this.basePremium = basePremium;
        this.territoryFactor = territoryFactor;
        this.classFactor = classFactor;
        this.experienceMod = experienceMod;
        this.territoryPremium = territoryPremium;
        this.classPremium = classPremium;
        this.modifiedPremium = modifiedPremium;
        this.taxRate = taxRate;
        this.taxAmount = taxAmount;
        this.surcharge = surcharge;
        this.finalPremium = finalPremium;
    }

    public String getPolicyType() {
        return policyType;
    }

    public BigDecimal getBasePremium() {
        return basePremium;
    }

    public BigDecimal getTerritoryFactor() {
        return territoryFactor;
    }

    public BigDecimal getClassFactor() {
        return classFactor;
    }

    public BigDecimal getExperienceMod() {
        return experienceMod;
    }

    public BigDecimal getTerritoryPremium() {
        return territoryPremium;
    }

    public BigDecimal getClassPremium() {
        return classPremium;
    }

    public BigDecimal getModifiedPremium() {
        return modifiedPremium;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getSurcharge() {
        return surcharge;
    }

    public BigDecimal getFinalPremium() {
        return finalPremium;
    }
}
