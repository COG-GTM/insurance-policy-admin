package com.acme.insurance.pas.rating;

import java.math.BigDecimal;

/**
 * Raised when an intermediate result would not fit its COBOL picture.
 * PREMBAT has no ON SIZE ERROR clause, so the mainframe silently drops the
 * high-order digits instead; the Java implementation fails loudly.
 */
public class PremiumOverflowException extends ArithmeticException {

    public PremiumOverflowException(String cobolField, BigDecimal value, BigDecimal max) {
        super(cobolField + " overflow: " + value.toPlainString()
                + " exceeds picture maximum " + max.toPlainString());
    }
}
