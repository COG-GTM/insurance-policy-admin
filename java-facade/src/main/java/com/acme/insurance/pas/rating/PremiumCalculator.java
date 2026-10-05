package com.acme.insurance.pas.rating;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Java implementation of the PREMBAT premium calculation
 * (cobol/programs/PREMBAT.cbl, paragraph 3200-CALCULATE-PREMIUM).
 *
 * Decimal semantics follow the COBOL working-storage pictures exactly:
 * every COMPUTE result is stored into a V99 field without ROUNDED, so each
 * intermediate is truncated toward zero to 2 decimal places. Using
 * HALF_UP rounding instead breaks parity on most rated inputs (673 of the
 * 840 factor-sweep fixtures).
 *
 * Parity with the COBOL program is verified by
 * modernization/prembat/run-parity.sh and PrembatParityTest.
 */
@Component
public class PremiumCalculator {

    /** WS-TAX-RATE PIC S9(01)V9999 VALUE 0.0350 (flat, not state-specific). */
    static final BigDecimal TAX_RATE = new BigDecimal("0.0350");

    /** Regulatory surcharge, flat per policy (2019-11-15 change). */
    static final BigDecimal REGULATORY_SURCHARGE = new BigDecimal("25.00");

    /** WHEN OTHER branch of the base-rate EVALUATE. */
    static final BigDecimal DEFAULT_BASE_PREMIUM = new BigDecimal("1000.00");

    /** PIC S9(09)V99 - WS-BASE/TERR/CLASS/MOD/FINAL-PREMIUM. */
    static final BigDecimal MAX_PREMIUM = new BigDecimal("999999999.99");

    /** PIC S9(07)V99 - WS-TAX-AMOUNT. */
    static final BigDecimal MAX_TAX = new BigDecimal("9999999.99");

    private static final int MONEY_SCALE = 2;

    private static final Map<String, BigDecimal> BASE_PREMIUMS;

    static {
        Map<String, BigDecimal> rates = new HashMap<String, BigDecimal>();
        rates.put("AUT", new BigDecimal("850.00"));
        rates.put("HOM", new BigDecimal("1200.00"));
        rates.put("COM", new BigDecimal("5000.00"));
        rates.put("LIF", new BigDecimal("400.00"));
        rates.put("HLT", new BigDecimal("3500.00"));
        BASE_PREMIUMS = Collections.unmodifiableMap(rates);
    }

    /** Calculates the premium exactly as PREMBAT does today (all factors 1.00). */
    public PremiumBreakdown calculate(String policyType) {
        return calculate(policyType, RatingFactors.LEGACY);
    }

    public PremiumBreakdown calculate(String policyType, RatingFactors factors) {
        BigDecimal base = basePremiumFor(policyType);
        BigDecimal territory = store("WS-TERR-PREMIUM",
                base.multiply(factors.getTerritoryFactor()), MAX_PREMIUM);
        BigDecimal classPremium = store("WS-CLASS-PREMIUM",
                territory.multiply(factors.getClassFactor()), MAX_PREMIUM);
        BigDecimal modified = store("WS-MOD-PREMIUM",
                classPremium.multiply(factors.getExperienceMod()), MAX_PREMIUM);
        BigDecimal tax = store("WS-TAX-AMOUNT", modified.multiply(TAX_RATE), MAX_TAX);
        BigDecimal surcharge = REGULATORY_SURCHARGE;
        BigDecimal finalPremium = store("WS-FINAL-PREMIUM",
                modified.add(tax).add(surcharge), MAX_PREMIUM);
        return new PremiumBreakdown(policyType, base, territory, classPremium, modified,
                tax, surcharge, finalPremium);
    }

    /**
     * Base rate lookup. Matching is exact on the 3-character POLICY-TYPE
     * (level-88 semantics): case-sensitive, and padded or unknown codes such as
     * 'aut', 'AU ' or 'CGL' fall through to the default rate.
     */
    static BigDecimal basePremiumFor(String policyType) {
        BigDecimal rate = policyType == null ? null : BASE_PREMIUMS.get(policyType);
        return rate != null ? rate : DEFAULT_BASE_PREMIUM;
    }

    private static BigDecimal store(String cobolField, BigDecimal value, BigDecimal max) {
        BigDecimal truncated = value.setScale(MONEY_SCALE, RoundingMode.DOWN);
        if (truncated.abs().compareTo(max) > 0) {
            throw new PremiumOverflowException(cobolField, value, max);
        }
        return truncated;
    }
}
