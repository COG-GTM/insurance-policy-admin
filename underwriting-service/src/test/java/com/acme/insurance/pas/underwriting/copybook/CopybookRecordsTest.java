package com.acme.insurance.pas.underwriting.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CopybookRecordsTest {

    private static PolicyRecord policy(String number, BigDecimal premium, BigDecimal limit, String branch) {
        return PolicyRecord.fromUnderwritingSelect(number, "COM", premium, limit, branch);
    }

    @Test
    void recordLengthsMatchCopybooksIncludingFiller() {
        assertThat(PolicyRecord.RECORD_LENGTH).isEqualTo(125);
        assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(365);
    }

    @Test
    void policyAcceptsMaximumFieldWidthsAndTrimsCharPadding() {
        PolicyRecord p = policy("POL-00000001", new BigDecimal("999999999.99"), new BigDecimal("99999999999.99"), "B001");
        assertThat(p.policyNumber()).isEqualTo("POL-00000001");
        assertThat(policy("POL-1       ", BigDecimal.ONE, BigDecimal.ONE, "B1  ").policyNumber()).isEqualTo("POL-1");
    }

    @Test
    void policyNumberLongerThanPicX12IsRejected() {
        assertThatThrownBy(() -> policy("POL-000000012", BigDecimal.ONE, BigDecimal.ONE, "B001"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("POLICY-NUMBER");
    }

    @Test
    void branchCodeLongerThanPicX4IsRejected() {
        assertThatThrownBy(() -> policy("P1", BigDecimal.ONE, BigDecimal.ONE, "B0001"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("POLICY-BRANCH-CODE");
    }

    @Test
    void comp3PremiumOverflowAndExtraScaleAreRejected() {
        assertThatThrownBy(() -> policy("P1", new BigDecimal("1000000000.00"), BigDecimal.ONE, "B001"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("POLICY-TOTAL-PREMIUM");
        assertThatThrownBy(() -> policy("P1", new BigDecimal("1.001"), BigDecimal.ONE, "B001"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("POLICY-TOTAL-PREMIUM");
    }

    @Test
    void comp3LimitOverflowIsRejected() {
        assertThatThrownBy(() -> policy("P1", BigDecimal.ONE, new BigDecimal("100000000000.00"), "B001"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("POLICY-LIMIT");
    }

    @Test
    void withDecisionSetsUwStatusAndThreeDigitRiskScore() {
        PolicyRecord p = policy("P1", BigDecimal.ONE, BigDecimal.ONE, "B001").withDecision("DC", 750);
        assertThat(p.uwStatus()).isEqualTo("DC");
        assertThat(p.riskScore()).isEqualTo(750);
        assertThatThrownBy(() -> p.withDecision("XX", 1)).isInstanceOf(CopybookFieldException.class);
        assertThatThrownBy(() -> p.withDecision("AP", 1000)).isInstanceOf(CopybookFieldException.class);
    }

    @Test
    void customerCreditScoreIsPic9_3AndTiersAre88Levels() {
        CustomerRecord c = CustomerRecord.fromUnderwritingSelect("C", 999, "P");
        assertThat(c.isTierPreferred()).isTrue();
        assertThat(c.isTierSubstandard()).isFalse();
        assertThat(CustomerRecord.fromUnderwritingSelect("I", 0, "U").isTierSubstandard()).isTrue();
        assertThat(CustomerRecord.fromUnderwritingSelect("I", 0, "S").isTierPreferred()).isFalse();
        assertThatThrownBy(() -> CustomerRecord.fromUnderwritingSelect("C", 1000, "S"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("CUST-CREDIT-SCORE");
        assertThatThrownBy(() -> CustomerRecord.fromUnderwritingSelect("C", -1, "S"))
                .isInstanceOf(CopybookFieldException.class);
    }

    @Test
    void customerTypeLongerThanPicX1IsRejected() {
        assertThatThrownBy(() -> CustomerRecord.fromUnderwritingSelect("CC", 700, "S"))
                .isInstanceOf(CopybookFieldException.class)
                .hasMessageContaining("CUST-TYPE");
    }
}
