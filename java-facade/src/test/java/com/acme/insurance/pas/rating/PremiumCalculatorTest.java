package com.acme.insurance.pas.rating;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;

/** Business rules extracted from PREMBAT 3200-CALCULATE-PREMIUM. */
public class PremiumCalculatorTest {

    private final PremiumCalculator calculator = new PremiumCalculator();

    @Test
    public void baseRatesByPolicyType() {
        assertFinal("AUT", "904.75");
        assertFinal("HOM", "1267.00");
        assertFinal("COM", "5200.00");
        assertFinal("LIF", "439.00");
        assertFinal("HLT", "3647.50");
    }

    @Test
    public void unknownOrMalformedTypesUseDefaultRate() {
        assertFinal("CGL", "1060.00");
        assertFinal("aut", "1060.00");
        assertFinal("AU ", "1060.00");
        assertFinal("   ", "1060.00");
        assertFinal(null, "1060.00");
    }

    @Test
    public void taxIsFlatThreePointFivePercentAndSurchargeIsFlat() {
        PremiumBreakdown b = calculator.calculate("HLT");
        assertEquals(new BigDecimal("122.50"), b.getTaxAmount());
        assertEquals(new BigDecimal("25.00"), b.getSurcharge());
    }

    @Test
    public void intermediateResultsAreTruncatedNotRounded() {
        // 850.00 * 0.8125 = 690.625 -> 690.62 (HALF_UP would give 690.63)
        PremiumBreakdown b = calculator.calculate("AUT", new RatingFactors(
                new BigDecimal("0.8125"), BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(new BigDecimal("690.62"), b.getTerritoryPremium());
        // 690.62 * 0.0350 = 24.1717 -> 24.17
        assertEquals(new BigDecimal("24.17"), b.getTaxAmount());
        assertEquals(new BigDecimal("739.79"), b.getFinalPremium());
    }

    @Test(expected = PremiumOverflowException.class)
    public void overflowFailsLoudlyInsteadOfDroppingHighOrderDigits() {
        BigDecimal big = new BigDecimal("999.9999");
        calculator.calculate("COM", new RatingFactors(big, big, BigDecimal.ONE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void factorsBeyondCobolPictureAreRejected() {
        new RatingFactors(new BigDecimal("1.00001"), BigDecimal.ONE, BigDecimal.ONE);
    }

    private void assertFinal(String type, String expected) {
        assertEquals(new BigDecimal(expected), calculator.calculate(type).getFinalPremium());
    }
}
