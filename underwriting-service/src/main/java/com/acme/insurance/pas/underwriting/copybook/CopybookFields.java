package com.acme.insurance.pas.underwriting.copybook;

import java.math.BigDecimal;

/**
 * Explicit PIC-clause validation used by the copybook records. Every check names the
 * copybook field so a violation can be traced straight back to the layout.
 */
public final class CopybookFields {

    private CopybookFields() {
    }

    /** {@code PIC X(n)}: null is treated as SPACES; longer values are rejected. */
    public static String alnum(String field, String value, int length) {
        if (value == null) {
            return "";
        }
        String trimmed = rtrim(value);
        if (trimmed.length() > length) {
            throw new CopybookFieldException(field,
                    "PIC X(" + length + ") holds at most " + length + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    /** {@code PIC 9(n)} (unsigned display): 0 .. 10^n - 1. */
    public static int unsigned(String field, int value, int digits) {
        if (value < 0 || value > maxUnsigned(digits)) {
            throw new CopybookFieldException(field,
                    "PIC 9(" + digits + ") holds 0.." + maxUnsigned(digits) + ", got " + value);
        }
        return value;
    }

    /** {@code PIC 9(8)} date in YYYYMMDD form; 0 means "not set". */
    public static int date(String field, int value) {
        unsigned(field, value, 8);
        return value;
    }

    /**
     * {@code PIC S9(i)V9(d) COMP-3}: at most {@code i} integer digits and {@code d} decimals.
     * Null is treated as zero (the COBOL field is initialised to packed zero).
     */
    public static BigDecimal packed(String field, BigDecimal value, int integerDigits, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale);
        }
        if (value.stripTrailingZeros().scale() > scale) {
            throw new CopybookFieldException(field,
                    "PIC S9(" + integerDigits + ")V" + "9".repeat(scale) + " allows " + scale + " decimals, got " + value);
        }
        BigDecimal scaled = value.setScale(scale);
        if (scaled.abs().compareTo(BigDecimal.TEN.pow(integerDigits)) >= 0) {
            throw new CopybookFieldException(field,
                    "PIC S9(" + integerDigits + ")V" + "9".repeat(scale) + " allows " + integerDigits
                            + " integer digits, got " + value);
        }
        return scaled;
    }

    /** {@code 88}-level check: value must be blank or one of the listed codes. */
    public static String oneOf(String field, String value, int length, String... allowed) {
        String v = alnum(field, value, length);
        if (v.isEmpty()) {
            return v;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new CopybookFieldException(field, "'" + v + "' is not a defined 88-level value");
    }

    static int maxUnsigned(int digits) {
        return (int) Math.pow(10, digits) - 1;
    }

    static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
