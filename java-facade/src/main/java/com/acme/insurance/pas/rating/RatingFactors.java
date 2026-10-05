package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Multiplicative rating factors applied to the base premium, in PREMBAT order:
 * territory, class, experience modification.
 *
 * Each factor matches the COBOL picture of the PREMBAT rating tables,
 * PIC S9(03)V9999 COMP-3 (up to 3 integer and 4 decimal digits).
 * PREMBAT currently hardcodes all three to 1.00; {@link #LEGACY} reproduces that.
 */
public final class RatingFactors {

    private static final BigDecimal MAX_FACTOR = new BigDecimal("999.9999");

    public static final RatingFactors LEGACY =
            new RatingFactors(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

    private final BigDecimal territoryFactor;
    private final BigDecimal classFactor;
    private final BigDecimal experienceMod;

    public RatingFactors(BigDecimal territoryFactor, BigDecimal classFactor,
                         BigDecimal experienceMod) {
        this.territoryFactor = validate("territoryFactor", territoryFactor);
        this.classFactor = validate("classFactor", classFactor);
        this.experienceMod = validate("experienceMod", experienceMod);
    }

    private static BigDecimal validate(String name, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.signum() < 0 || value.compareTo(MAX_FACTOR) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + MAX_FACTOR + ": " + value);
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException(
                    name + " has more than 4 decimal places (PIC S9(03)V9999): " + value);
        }
        return value;
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
}
