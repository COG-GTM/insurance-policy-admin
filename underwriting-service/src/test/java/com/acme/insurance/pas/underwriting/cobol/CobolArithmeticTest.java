package com.acme.insurance.pas.underwriting.cobol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CobolArithmeticTest {

    @ParameterizedTest(name = "store {0} into PIC 9({1}) -> {2}")
    @CsvSource({"75,4,75", "-25,4,25", "-100,4,100", "1750,4,1750", "10123,4,123", "1750,3,750", "999,3,999",
            "1000,3,0", "0,4,0"})
    void storeUnsignedDropsSignAndHighOrderDigits(long value, int digits, int expected) {
        assertThat(CobolArithmetic.storeUnsigned(value, digits)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource({"249,2,124", "-50,2,-25", "-99,2,-49", "899,2,449"})
    void divideTruncatesTowardZero(long a, long b, long expected) {
        assertThat(CobolArithmetic.divideTruncated(a, b)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} / {1} at {2} dp -> {3}")
    @CsvSource({"805.00,1000.00,2,0.80", "800.00,1000.00,2,0.80", "10.00,3.00,2,3.33", "-10.00,3.00,2,-3.33"})
    void divideIntermediateTruncates(String a, String b, int dp, String expected) {
        assertThat(CobolArithmetic.divideIntermediate(new BigDecimal(a), new BigDecimal(b), dp))
                .isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "store {0} into S9(3)V99 -> {1}")
    @CsvSource({"80.999,80.99", "1050.00,50.00", "1250.00,250.00", "-1250.00,-250.00", "999.99,999.99",
            "1000.00,0.00"})
    void storeSignedTruncatesBothEnds(String value, String expected) {
        BigDecimal stored = CobolArithmetic.storeSigned(new BigDecimal(value), 3, 2);
        assertThat(stored).isEqualByComparingTo(expected);
        assertThat(stored.scale()).isEqualTo(2);
    }
}
