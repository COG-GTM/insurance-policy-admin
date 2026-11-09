package com.acme.insurance.pas.underwriting.cobol;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reproduces IBM Enterprise COBOL fixed-point semantics used by UNDWRT
 * (no ROUNDED, no ON SIZE ERROR, ARITH(COMPAT)).
 */
public final class CobolArithmetic {

    private CobolArithmetic() {
    }

    /**
     * Store an arithmetic result into an unsigned {@code PIC 9(n)} receiver: the sign is
     * dropped (absolute value) and high-order digits beyond {@code n} are truncated.
     * E.g. {@code SUBTRACT 100 FROM WS-RISK-SCORE} with a score of 25 stores 75.
     */
    public static int storeUnsigned(long value, int digits) {
        long modulus = (long) Math.pow(10, digits);
        return (int) (Math.abs(value) % modulus);
    }

    /**
     * {@code MOVE} of an unsigned integer into a shorter {@code PIC 9(n)} field keeps the
     * low-order {@code n} digits (e.g. WS-RISK-SCORE 9(04) 1750 -> POLICY-RISK-SCORE 9(03) 750).
     */
    public static int moveUnsigned(int value, int digits) {
        return storeUnsigned(value, digits);
    }

    /**
     * Integer division as COBOL computes {@code COMPUTE X = a / b} when every operand and the
     * receiver are integers: the quotient is truncated toward zero.
     */
    public static long divideTruncated(long dividend, long divisor) {
        return dividend / divisor;
    }

    /**
     * Intermediate result of {@code a / b} inside a COMPUTE: IBM keeps
     * {@code max(d1 - d2, dmax)} decimal places and truncates the rest.
     */
    public static BigDecimal divideIntermediate(BigDecimal dividend, BigDecimal divisor, int decimalPlaces) {
        return dividend.divide(divisor, decimalPlaces, RoundingMode.DOWN);
    }

    /**
     * Store into {@code PIC S9(i)V9(d)} (COMP-3 or display) without ROUNDED or ON SIZE ERROR:
     * excess decimals are truncated and excess high-order integer digits are dropped, sign kept.
     */
    public static BigDecimal storeSigned(BigDecimal value, int integerDigits, int scale) {
        BigDecimal truncated = value.setScale(scale, RoundingMode.DOWN);
        BigDecimal modulus = BigDecimal.TEN.pow(integerDigits);
        return truncated.remainder(modulus).setScale(scale);
    }
}
