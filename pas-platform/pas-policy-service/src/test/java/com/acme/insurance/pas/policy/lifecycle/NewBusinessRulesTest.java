package com.acme.insurance.pas.policy.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewBusinessRulesTest {

  static final LocalDate TODAY = LocalDate.of(2025, 6, 1);

  static CreatePolicyRequest request(String holder, String type, LocalDate eff, String limit) {
    return new CreatePolicyRequest(
        holder,
        type,
        eff,
        eff == null ? null : eff.plusYears(1),
        limit == null ? null : new BigDecimal(limit),
        new BigDecimal("1200"),
        new BigDecimal("500.5"),
        "AG0001",
        "B001");
  }

  @Test
  void effectiveTodayIsAccepted() {
    assertThat(NewBusinessRules.validate(request("C1", "AUT", TODAY, "1"), TODAY, true)).isEmpty();
  }

  @Test
  void lastFailingCheckWins() {
    assertThat(NewBusinessRules.validate(request(null, null, TODAY.minusDays(1), "0"), TODAY, true))
        .contains(NewBusinessRules.LIMIT_ZERO);
    assertThat(
            NewBusinessRules.validate(request(null, null, TODAY.minusDays(1), "0"), TODAY, false))
        .contains(NewBusinessRules.HOLDER_NOT_FOUND);
    assertThat(NewBusinessRules.validate(request(null, "AUT", TODAY, "1"), TODAY, true))
        .contains(NewBusinessRules.HOLDER_REQUIRED);
    assertThat(NewBusinessRules.validate(request("C1", " ", TODAY, "1"), TODAY, true))
        .contains(NewBusinessRules.TYPE_REQUIRED);
  }

  @Test
  void negativeLimitPassesAtParity() {
    assertThat(NewBusinessRules.validate(request("C1", "AUT", TODAY, "-1"), TODAY, true)).isEmpty();
  }

  @Test
  void newPolicyAndCoveragesCarryRequestDatesAndAmounts() {
    PolicyEntity p =
        NewBusinessRules.newPolicy(
            request("C1", "COM", TODAY, "5000000"), "POL001000000", LocalDateTime.MIN);
    assertThat(p.getPolicyStatus()).isEqualTo("PN");
    assertThat(p.getUwStatus()).isEqualTo("PN");
    assertThat(p.getInceptionDate()).isEqualTo(TODAY);
    assertThat(p.getDeductible()).hasToString("500.50");
    assertThat(p.getUpdatedBy()).isEqualTo("POLNEW");
    List<CoverageEntity> covs = NewBusinessRules.defaultCoverages(p);
    assertThat(covs)
        .extracting(CoverageEntity::getCoverageType)
        .containsExactly("PROP", "LIAB", "WKCP");
    assertThat(covs)
        .extracting(c -> c.getId().getSequenceNum())
        .containsExactly((short) 1, (short) 2, (short) 3);
    assertThat(covs)
        .allSatisfy(
            c -> {
              assertThat(c.getCoverageLimit()).hasToString("5000000.00");
              assertThat(c.getPremium()).hasToString("0.00");
              assertThat(c.getExpiryDate()).isEqualTo(TODAY.plusYears(1));
              assertThat(c.getCoinsurancePct()).isEqualTo((short) 100);
            });
    assertThat(NewBusinessRules.defaultCoveragesFor("LIF")).isEmpty();
    assertThat(NewBusinessRules.defaultCoveragesFor("XYZ")).isEmpty();
  }
}
