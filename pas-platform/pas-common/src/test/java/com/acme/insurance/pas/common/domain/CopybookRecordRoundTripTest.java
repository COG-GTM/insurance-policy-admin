package com.acme.insurance.pas.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.common.copybook.CopybookDataException;
import com.acme.insurance.pas.common.copybook.PasCopybooks;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Copybook bytes <-> domain record, in EBCDIC and ASCII, at the PIC limits. */
class CopybookRecordRoundTripTest {

  static PolicyRecord maxPolicy() {
    return new PolicyRecord(
        "POL000100000",
        "AUT",
        "AC",
        LocalDate.of(2024, 2, 29),
        LocalDate.of(9999, 12, 31),
        "CUST000001",
        "AG0001",
        "BR01",
        new BigDecimal("999999999.99"),
        new BigDecimal("-9999999.99"),
        new BigDecimal("99999999999.99"),
        null,
        999,
        "AP",
        0,
        "Y",
        "N",
        LocalDate.of(2025, 1, 1),
        "SYSTEM");
  }

  static PremiumRecord minPremium() {
    return new PremiumRecord(
        "POL000100000",
        1,
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2025, 1, 1),
        new BigDecimal("-9999999.9999"),
        new BigDecimal("999.9999"),
        new BigDecimal("-999.9999"),
        new BigDecimal("0.0001"),
        new BigDecimal("1.0000"),
        new BigDecimal("-999.99"),
        new BigDecimal("-9999999.99"),
        new BigDecimal("0.00"),
        new BigDecimal("-999999999.99"),
        "MO",
        new BigDecimal("9999999.99"),
        null,
        "PREMBAT");
  }

  static CoverageRecord coverage() {
    return new CoverageRecord(
        "POL000100000",
        999,
        "CYBR",
        "Cyber liability - first party",
        new BigDecimal("-99999999999.99"),
        new BigDecimal("0.00"),
        new BigDecimal("0.01"),
        LocalDate.of(2024, 1, 1),
        LocalDate.of(2025, 1, 1),
        "PN",
        100,
        "TX0001",
        "C1234");
  }

  static CustomerRecord customer() {
    return new CustomerRecord(
        "CUST000001",
        "I",
        "Doe",
        "Jane",
        "Q",
        "",
        "1 Main St",
        "",
        "Austin",
        "TX",
        "73301",
        "USA",
        "5125550100",
        "jane@example.com",
        LocalDate.of(1980, 7, 4),
        "6789",
        "123456789",
        850,
        "P",
        "Y",
        LocalDate.of(2020, 1, 2),
        null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"IBM037", "US-ASCII"})
  void recordsRoundTrip(String charsetName) {
    Charset cs = Charset.forName(charsetName);
    byte[] policy = maxPolicy().toBytes(cs);
    assertThat(policy).hasSize(PasCopybooks.POLICY_RECORD.length());
    assertThat(PolicyRecord.fromBytes(policy, cs)).isEqualTo(maxPolicy());
    assertThat(CoverageRecord.fromBytes(coverage().toBytes(cs), cs)).isEqualTo(coverage());
    assertThat(PremiumRecord.fromBytes(minPremium().toBytes(cs), cs)).isEqualTo(minPremium());
    assertThat(CustomerRecord.fromBytes(customer().toBytes(cs), cs)).isEqualTo(customer());
    assertThat(PolicyRecord.fromBytes(policy, cs).toBytes(cs)).isEqualTo(policy);
  }

  @Test
  void decodedDecimalsCarryPicScale() {
    PremiumRecord r =
        PremiumRecord.fromBytes(
            minPremium().toBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    assertThat(r.baseRate().scale()).isEqualTo(4);
    assertThat(r.discountPct().scale()).isEqualTo(2);
    assertThat(r.totalPremium().scale()).isEqualTo(2);
  }

  @Test
  void enumsResolveFromRawCodesAndUnknownCodesAreKept() {
    PolicyRecord p = maxPolicy();
    assertThat(p.policyType()).contains(PolicyType.AUTO);
    assertThat(p.policyStatus()).contains(PolicyStatus.ACTIVE);
    assertThat(p.uwStatus()).contains(UnderwritingStatus.APPROVED);
    assertThat(coverage().coverageType()).contains(CoverageType.CYBER);
    assertThat(minPremium().installment()).contains(InstallmentCode.MONTHLY);
    assertThat(customer().riskTier()).contains(RiskTier.PREFERRED);
    assertThat(CopybookCode.lookup(PolicyType.class, "XYZ")).isEmpty();
    assertThat(CopybookCode.lookup(CoverageStatus.class, "AC ")).contains(CoverageStatus.ACTIVE);
    assertThat(PolicyType.COMMERCIAL.conditionName()).isEqualTo("POL-TYPE-COMM");
  }

  @Test
  void rejectsValuesBeyondPic() {
    PolicyRecord p = maxPolicy();
    PolicyRecord tooBig =
        new PolicyRecord(
            p.policyNumber(),
            p.policyTypeCode(),
            p.policyStatusCode(),
            p.effectiveDate(),
            p.expiryDate(),
            p.holderId(),
            p.agentCode(),
            p.branchCode(),
            new BigDecimal("1000000000.00"),
            p.deductible(),
            p.coverageLimit(),
            p.inceptionDate(),
            p.renewalCount(),
            p.uwStatusCode(),
            p.riskScore(),
            p.webIndicator(),
            p.apiFlag(),
            p.lastUpdated(),
            p.updatedBy());
    assertThatThrownBy(() -> tooBig.toBytes(StandardCharsets.US_ASCII))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("POLICY-TOTAL-PREMIUM");
  }

  @Test
  void customerToStringRedactsPii() {
    assertThat(customer().toString())
        .contains("CUST000001")
        .doesNotContain("6789", "123456789", "1980", "850", "Jane", "jane@example.com");
  }

  @Test
  void policyNumbersReproducePolnewTruncation() {
    assertThat(PolicyNumbers.fromSequence(1_000_000)).isEqualTo("POL000100000");
    assertThat(PolicyNumbers.fromSequence(1_000_009)).isEqualTo("POL000100000");
    assertThat(PolicyNumbers.fromSequence(1_000_010)).isEqualTo("POL000100001");
    assertThat(PolicyNumbers.fromSequence(2_147_483_647L)).isEqualTo("POL214748364");
    assertThatThrownBy(() -> PolicyNumbers.fromSequence(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
