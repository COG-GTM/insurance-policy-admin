package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyCodeTest {

  @Test
  void decodesKnownCodesIgnoringCharPadding() {
    assertThat(PolicyType.fromCode("AUT")).contains(PolicyType.AUTO);
    assertThat(CoverageType.fromCode("WKCP")).contains(CoverageType.WORKERS_COMP);
    assertThat(PolicyStatus.fromCode("PN")).contains(PolicyStatus.PENDING);
    assertThat(RiskTier.fromCode("U ")).contains(RiskTier.SUBSTANDARD);
  }

  @Test
  void unknownCodesAreKeptRawAndDecodeToEmpty() {
    Policy policy =
        new Policy(
            "POL-1", "CGL", "ZZ", null, null, null, null, null, null, null, null, null, null, "RM",
            null, null, null, null, null);

    assertThat(policy.policyTypeCode()).isEqualTo("CGL");
    assertThat(policy.policyType()).isEmpty();
    assertThat(policy.policyStatus()).isEmpty();
    assertThat(PolicyType.fromCode(null)).isEmpty();
  }

  @Test
  void underwritingStatusCoversCodesWrittenByUndwrt() {
    assertThat(UnderwritingStatus.fromCode("RS")).contains(UnderwritingStatus.REFERRED_SENIOR);
    assertThat(UnderwritingStatus.fromCode("RM")).contains(UnderwritingStatus.REFERRED_MANAGER);
    assertThat(
            Arrays.stream(UnderwritingStatus.values())
                .filter(UnderwritingStatus::inCopybookDomain)
                .map(UnderwritingStatus::code))
        .containsExactly("AP", "PN", "DC", "RF");
  }

  @Test
  void codesFitTheirColumns() {
    List<List<? extends LegacyCode>> all =
        List.of(
            List.of(PolicyType.values()), List.of(PolicyStatus.values()),
            List.of(UnderwritingStatus.values()), List.of(CoverageType.values()),
            List.of(CoverageStatus.values()), List.of(CustomerType.values()),
            List.of(RiskTier.values()), List.of(InstallmentCode.values()),
            List.of(EndorsementType.values()), List.of(YesNo.values()));
    int[] widths = {3, 2, 2, 4, 2, 1, 1, 2, 3, 1};
    for (int i = 0; i < all.size(); i++) {
      int width = widths[i];
      assertThat(all.get(i)).allSatisfy(c -> assertThat(c.code()).hasSize(width));
    }
  }
}
