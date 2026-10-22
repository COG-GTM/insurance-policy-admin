package com.acme.insurance.pas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DomainModelTest {
  @Test
  void normalizesDecimalScalesAndRejectsPrecisionLoss() {
    Coverage coverage =
        new Coverage(
            "POL000100000",
            1,
            Coded.of(CoverageType.PROPERTY),
            "Property",
            new BigDecimal("12"),
            new BigDecimal("1.2"),
            new BigDecimal("3.00"),
            null,
            null,
            Coded.of(CoverageStatus.ACTIVE),
            100,
            null,
            null);

    assertThat(coverage.coverageLimit()).isEqualByComparingTo("12.00");
    assertThat(coverage.deductible()).isEqualByComparingTo("1.20");
    assertThatThrownBy(
            () ->
                new Coverage(
                    "POL000100000",
                    1,
                    Coded.of(CoverageType.PROPERTY),
                    "Property",
                    new BigDecimal("12.001"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    null,
                    null,
                    Coded.of(CoverageStatus.ACTIVE),
                    100,
                    null,
                    null))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void normalizesTimestampsToUtcAndMasksSensitiveValuesInToString() {
    OffsetDateTime nonUtc = OffsetDateTime.parse("2024-01-01T01:00:00+01:00");
    PolicyHolder holder =
        new PolicyHolder(
            "CUST000001",
            Coded.of(CustomerType.INDIVIDUAL),
            "FamilyNameSecret",
            null,
            null,
            null,
            "123 Main Street",
            null,
            "Albany",
            "NY",
            "10001",
            "USA",
            "5551234567",
            "person@example.test",
            null,
            "4831",
            "987654320",
            720,
            Coded.of(RiskTier.STANDARD),
            Coded.of(YesNo.NO),
            null,
            nonUtc);
    String rendered = holder.toString();

    assertThat(holder.lastUpdated().getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(rendered)
        .contains("custId=****")
        .contains("lastName=****")
        .contains("firstName=null")
        .contains("addrLine1=****")
        .contains("dateOfBirth=null")
        .contains("ssnLast4=****")
        .contains("taxId=****")
        .contains("creditScore=****")
        .doesNotContain(
            "CUST000001",
            "FamilyNameSecret",
            "123 Main Street",
            "5551234567",
            "person@example.test",
            "987654320");
  }
}
