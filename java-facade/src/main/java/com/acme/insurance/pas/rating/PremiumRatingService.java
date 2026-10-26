package com.acme.insurance.pas.rating;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Applies PREMBAT 3200-CALCULATE-PREMIUM using COBOL-compatible decimal rules.
 */
@Service
public class PremiumRatingService {

    private static final BigDecimal ONE = new BigDecimal("1.0000");
    private static final BigDecimal TAX_RATE = new BigDecimal("0.0350");
    private static final BigDecimal SURCHARGE = new BigDecimal("25.00");
    private static final BigDecimal TEN = BigDecimal.TEN;

    public PremiumRating rate(String policyType) {
        return rate(policyType, ONE, ONE, ONE);
    }

    public PremiumRating rate(String policyType, BigDecimal territoryFactor,
            BigDecimal classFactor, BigDecimal experienceMod) {
        BigDecimal normalizedTerritoryFactor = validateFactor(territoryFactor);
        BigDecimal normalizedClassFactor = validateFactor(classFactor);
        BigDecimal normalizedExperienceMod = validateFactor(experienceMod);
        String normalizedPolicyType = normalizePolicyType(policyType);
        BigDecimal basePremium = basePremium(normalizedPolicyType);

        BigDecimal territoryPremium = picAmount(
                basePremium.multiply(normalizedTerritoryFactor), 9);
        BigDecimal classPremium = picAmount(
                territoryPremium.multiply(normalizedClassFactor), 9);
        BigDecimal modifiedPremium = picAmount(
                classPremium.multiply(normalizedExperienceMod), 9);
        BigDecimal taxAmount = picAmount(modifiedPremium.multiply(TAX_RATE), 7);
        BigDecimal finalPremium = picAmount(
                modifiedPremium.add(taxAmount).add(SURCHARGE), 9);

        return new PremiumRating(normalizedPolicyType, basePremium,
                normalizedTerritoryFactor, normalizedClassFactor,
                normalizedExperienceMod, territoryPremium, classPremium,
                modifiedPremium, TAX_RATE, taxAmount, SURCHARGE, finalPremium);
    }

    private BigDecimal validateFactor(BigDecimal factor) {
        if (factor == null) {
            throw new IllegalArgumentException(
                    "Factors must fit S9(03)V9999 without truncation");
        }
        BigDecimal scaled;
        try {
            scaled = factor.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Factors must fit S9(03)V9999 without truncation", e);
        }
        if (scaled.abs().compareTo(new BigDecimal("1000")) >= 0) {
            throw new IllegalArgumentException(
                    "Factors must fit S9(03)V9999 without truncation");
        }
        return scaled;
    }

    private String normalizePolicyType(String policyType) {
        if (policyType == null) {
            return "   ";
        }
        if (policyType.length() >= 3) {
            return policyType.substring(0, 3);
        }
        StringBuilder normalized = new StringBuilder(policyType);
        while (normalized.length() < 3) {
            normalized.append(' ');
        }
        return normalized.toString();
    }

    private BigDecimal basePremium(String policyType) {
        switch (policyType) {
            case "AUT":
                return new BigDecimal("850.00");
            case "HOM":
                return new BigDecimal("1200.00");
            case "COM":
                return new BigDecimal("5000.00");
            case "LIF":
                return new BigDecimal("400.00");
            case "HLT":
                return new BigDecimal("3500.00");
            default:
                return new BigDecimal("1000.00");
        }
    }

    private BigDecimal picAmount(BigDecimal value, int integerDigits) {
        BigDecimal truncated = value.setScale(2, RoundingMode.DOWN);
        return truncated.remainder(TEN.pow(integerDigits));
    }
}
