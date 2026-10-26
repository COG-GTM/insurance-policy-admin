package com.acme.insurance.pas.rating;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class PremiumRatingServiceParityTest {

    private final String policyType;
    private final BigDecimal territoryFactor;
    private final BigDecimal classFactor;
    private final BigDecimal experienceMod;
    private final BigDecimal basePremium;
    private final BigDecimal territoryPremium;
    private final BigDecimal classPremium;
    private final BigDecimal modifiedPremium;
    private final BigDecimal taxAmount;
    private final BigDecimal surcharge;
    private final BigDecimal finalPremium;

    public PremiumRatingServiceParityTest(String policyType,
            BigDecimal territoryFactor, BigDecimal classFactor,
            BigDecimal experienceMod, BigDecimal basePremium,
            BigDecimal territoryPremium, BigDecimal classPremium,
            BigDecimal modifiedPremium, BigDecimal taxAmount,
            BigDecimal surcharge, BigDecimal finalPremium) {
        this.policyType = policyType;
        this.territoryFactor = territoryFactor;
        this.classFactor = classFactor;
        this.experienceMod = experienceMod;
        this.basePremium = basePremium;
        this.territoryPremium = territoryPremium;
        this.classPremium = classPremium;
        this.modifiedPremium = modifiedPremium;
        this.taxAmount = taxAmount;
        this.surcharge = surcharge;
        this.finalPremium = finalPremium;
    }

    @Parameterized.Parameters(name = "{index}: {0}/{1}/{2}/{3}")
    public static Collection<Object[]> cases() throws IOException {
        List<Object[]> cases = new ArrayList<Object[]>();
        InputStream resource = PremiumRatingServiceParityTest.class
                .getResourceAsStream("/prembat-parity.csv");
        if (resource == null) {
            throw new IOException("Missing /prembat-parity.csv");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                String policyType = values[0].substring(1, values[0].length() - 1);
                cases.add(new Object[] {
                        policyType,
                        new BigDecimal(values[1]),
                        new BigDecimal(values[2]),
                        new BigDecimal(values[3]),
                        new BigDecimal(values[4]),
                        new BigDecimal(values[5]),
                        new BigDecimal(values[6]),
                        new BigDecimal(values[7]),
                        new BigDecimal(values[8]),
                        new BigDecimal(values[9]),
                        new BigDecimal(values[10])
                });
            }
        }
        assertEquals("Expected exactly 30 COBOL parity rows", 30, cases.size());
        return cases;
    }

    @Test
    public void matchesCobolParityRow() {
        PremiumRating result = new PremiumRatingService().rate(policyType,
                territoryFactor, classFactor, experienceMod);
        assertEquals(basePremium, result.getBasePremium());
        assertEquals(territoryPremium, result.getTerritoryPremium());
        assertEquals(classPremium, result.getClassPremium());
        assertEquals(modifiedPremium, result.getModifiedPremium());
        assertEquals(taxAmount, result.getTaxAmount());
        assertEquals(surcharge, result.getSurcharge());
        assertEquals(finalPremium, result.getFinalPremium());
    }
}
