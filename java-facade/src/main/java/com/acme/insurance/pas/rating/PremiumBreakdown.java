package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Result of a premium calculation. Field-for-field equivalent of the
 * PREMBAT WS-CALC-FIELDS working-storage group.
 */
public final class PremiumBreakdown {

    private final String policyType;
    private final BigDecimal basePremium;
    private final BigDecimal territoryPremium;
    private final BigDecimal classPremium;
    private final BigDecimal modifiedPremium;
    private final BigDecimal taxAmount;
    private final BigDecimal surcharge;
    private final BigDecimal finalPremium;

    PremiumBreakdown(String policyType, BigDecimal basePremium, BigDecimal territoryPremium,
                     BigDecimal classPremium, BigDecimal modifiedPremium,
                     BigDecimal taxAmount, BigDecimal surcharge, BigDecimal finalPremium) {
        this.policyType = policyType;
        this.basePremium = basePremium;
        this.territoryPremium = territoryPremium;
        this.classPremium = classPremium;
        this.modifiedPremium = modifiedPremium;
        this.taxAmount = taxAmount;
        this.surcharge = surcharge;
        this.finalPremium = finalPremium;
    }

    public String getPolicyType() {
        return policyType;
    }

    /** WS-BASE-PREMIUM */
    public BigDecimal getBasePremium() {
        return basePremium;
    }

    /** WS-TERR-PREMIUM */
    public BigDecimal getTerritoryPremium() {
        return territoryPremium;
    }

    /** WS-CLASS-PREMIUM */
    public BigDecimal getClassPremium() {
        return classPremium;
    }

    /** WS-MOD-PREMIUM */
    public BigDecimal getModifiedPremium() {
        return modifiedPremium;
    }

    /** WS-TAX-AMOUNT */
    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    /** WS-SURCHARGE */
    public BigDecimal getSurcharge() {
        return surcharge;
    }

    /** WS-FINAL-PREMIUM */
    public BigDecimal getFinalPremium() {
        return finalPremium;
    }
}
