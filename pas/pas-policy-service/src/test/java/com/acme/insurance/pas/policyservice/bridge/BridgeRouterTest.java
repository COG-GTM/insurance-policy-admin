package com.acme.insurance.pas.policyservice.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.copybook.PolicyRecordCodec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BridgeRouterTest {

  private static Policy policy(Integer renewalCount, Integer riskScore) {
    return new Policy(
        "POL-00000001",
        "HO",
        "AC",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        "C000000001",
        "AG0001",
        "BR01",
        new BigDecimal("1200.00"),
        new BigDecimal("500.00"),
        new BigDecimal("250000.00"),
        LocalDate.of(2020, 1, 1),
        renewalCount,
        "RF",
        riskScore,
        "N",
        "Y",
        LocalDateTime.of(2026, 1, 1, 9, 30),
        "PASAPI");
  }

  @Test
  void scoresAboveNineNineNineKeepTheirLastThreeDigitsLikeTheCobolMove() {
    PolicyRecordCodec codec = new PolicyRecordCodec();
    byte[] record = codec.encode(BridgeRouter.legacyView(policy(1004, 1450)));
    assertThat(record).hasSize(125);
    Policy decoded = codec.decode(record);
    assertThat(decoded.riskScore()).isEqualTo(450);
    assertThat(decoded.renewalCount()).isEqualTo(4);
  }

  @Test
  void valuesThatFitAndNullsAreUnchanged() {
    assertThat(BridgeRouter.legacyView(policy(3, 999)).riskScore()).isEqualTo(999);
    assertThat(BridgeRouter.legacyView(policy(null, null)))
        .extracting(Policy::riskScore, Policy::renewalCount)
        .containsOnlyNulls();
  }
}
