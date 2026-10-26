package com.acme.insurance.pas.rating;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PremiumRatingServiceTest {

    private final PremiumRatingService service = new PremiumRatingService();

    @Test
    public void oneArgumentRateUsesProductionFactors() {
        assertEquals(new BigDecimal("850.00"), service.rate("AUT").getBasePremium());
        assertEquals(new BigDecimal("1200.00"), service.rate("HOM").getBasePremium());
        assertEquals(new BigDecimal("5000.00"), service.rate("COM").getBasePremium());
        assertEquals(new BigDecimal("400.00"), service.rate("LIF").getBasePremium());
        assertEquals(new BigDecimal("3500.00"), service.rate("HLT").getBasePremium());
        assertEquals(new BigDecimal("1000.00"), service.rate("CGL").getBasePremium());
        assertEquals(new BigDecimal("1.0000"),
                service.rate("AUT").getTerritoryFactor());
    }

    @Test
    public void policyTypeFollowsCobolMoveToPicX03() {
        assertPolicyType("AUTO", "AUT", "850.00");
        assertPolicyType("AU", "AU ", "1000.00");
        assertPolicyType("hom", "hom", "1000.00");
        assertPolicyType(null, "   ", "1000.00");
    }

    @Test
    public void rejectsInvalidFactorsWithoutTruncating() {
        assertInvalidFactors(null, new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        assertInvalidFactors(new BigDecimal("1.00001"), new BigDecimal("1.0000"),
                new BigDecimal("1.0000"));
        assertInvalidFactors(new BigDecimal("1000.0000"), new BigDecimal("1.0000"),
                new BigDecimal("1.0000"));
        assertInvalidFactors(new BigDecimal("-1000.0000"), new BigDecimal("1.0000"),
                new BigDecimal("1.0000"));
        assertInvalidFactors(new BigDecimal("1.0000"), null, new BigDecimal("1.0000"));
        assertInvalidFactors(new BigDecimal("1.0000"), new BigDecimal("1.0000"), null);
    }

    @Test
    public void acceptsLargestRepresentablePositiveAndNegativeFactors() {
        PremiumRating redundantTrailingZero = service.rate("AUT",
                new BigDecimal("1.00000"), new BigDecimal("1.0000"),
                new BigDecimal("1.0000"));
        PremiumRating positive = service.rate("AUT", new BigDecimal("999.9999"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        PremiumRating negative = service.rate("AUT", new BigDecimal("-999.9999"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        assertEquals(new BigDecimal("1.0000"),
                redundantTrailingZero.getTerritoryFactor());
        assertEquals(new BigDecimal("999.9999"), positive.getTerritoryFactor());
        assertEquals(new BigDecimal("-999.9999"), negative.getTerritoryFactor());
    }

    @Test
    public void truncatesInsteadOfRounding() {
        PremiumRating result = service.rate("AUT", new BigDecimal("1.0500"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        assertEquals(new BigDecimal("31.23"), result.getTaxAmount());
    }

    @Test
    public void truncatesNegativeValuesTowardZero() {
        PremiumRating result = service.rate("AUT", new BigDecimal("-1.5000"),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"));
        assertEquals(new BigDecimal("-44.62"), result.getTaxAmount());
        assertEquals(new BigDecimal("-1294.62"), result.getFinalPremium());
    }

    private void assertPolicyType(String input, String normalized,
            String expectedBase) {
        PremiumRating result = service.rate(input);
        assertEquals(normalized, result.getPolicyType());
        assertEquals(new BigDecimal(expectedBase), result.getBasePremium());
    }

    private void assertInvalidFactors(BigDecimal territoryFactor,
            BigDecimal classFactor, BigDecimal experienceMod) {
        try {
            service.rate("AUT", territoryFactor, classFactor, experienceMod);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
