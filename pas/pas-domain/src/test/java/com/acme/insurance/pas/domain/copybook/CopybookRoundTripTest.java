package com.acme.insurance.pas.domain.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.Premium;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CopybookRoundTripTest {

  static Stream<Charset> charsets() {
    return Stream.of(CopybookCodec.EBCDIC, StandardCharsets.ISO_8859_1);
  }

  static Policy policy(BigDecimal premium, BigDecimal deductible, BigDecimal limit) {
    return new Policy(
        "POL-00012345",
        "AUT",
        "AC",
        LocalDate.of(2024, 2, 29),
        LocalDate.of(2025, 2, 28),
        "CUST000001",
        "AG0001",
        "NY01",
        premium,
        deductible,
        limit,
        LocalDate.of(2019, 1, 1),
        999,
        "RS",
        999,
        "Y",
        "N",
        LocalDateTime.of(2024, 3, 1, 0, 0),
        "POLNEW");
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void policyRoundTripsAtTheLargestAndSmallestPackedValues(Charset charset) {
    PolicyRecordCodec codec = new PolicyRecordCodec(charset);
    for (Policy policy :
        new Policy[] {
          policy(
              new BigDecimal("999999999.99"),
              new BigDecimal("9999999.99"),
              new BigDecimal("99999999999.99")),
          policy(
              new BigDecimal("-999999999.99"),
              new BigDecimal("-9999999.99"),
              new BigDecimal("-99999999999.99")),
          policy(new BigDecimal("0.00"), new BigDecimal("-0.01"), new BigDecimal("0.01")),
        }) {
      byte[] bytes = codec.encode(policy);

      assertThat(bytes).hasSize(125);
      assertThat(codec.decode(bytes)).isEqualTo(policy);
      assertThat(codec.encode(codec.decode(bytes))).isEqualTo(bytes);
    }
  }

  @Test
  void policyPackedFieldsSitAtTheCopybookOffsets() {
    Policy policy =
        policy(new BigDecimal("1234.56"), new BigDecimal("-500.00"), new BigDecimal("1000000.00"));

    byte[] bytes = new PolicyRecordCodec().encode(policy);

    HexFormat hex = HexFormat.of().withUpperCase();
    assertThat(hex.formatHex(bytes, 53, 59)).isEqualTo("00000123456C");
    assertThat(hex.formatHex(bytes, 59, 64)).isEqualTo("000050000D");
    assertThat(hex.formatHex(bytes, 64, 71)).isEqualTo("0000100000000C");
    assertThat(new String(bytes, 0, 12, CopybookCodec.EBCDIC)).isEqualTo("POL-00012345");
    assertThat(new String(bytes, 17, 8, CopybookCodec.EBCDIC)).isEqualTo("20240229");
    assertThat(new String(bytes, 105, 20, CopybookCodec.EBCDIC)).isBlank();
  }

  @Test
  void nullsAndBlanksBecomeSpacesAndZeros() {
    Policy sparse =
        new Policy(
            "POL1", null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    PolicyRecordCodec codec = new PolicyRecordCodec(StandardCharsets.ISO_8859_1);

    byte[] bytes = codec.encode(sparse);
    Policy decoded = codec.decode(bytes);

    assertThat(new String(bytes, 4, 13, StandardCharsets.ISO_8859_1)).isBlank();
    assertThat(new String(bytes, 17, 16, StandardCharsets.ISO_8859_1)).isEqualTo("0".repeat(16));
    assertThat(decoded.policyTypeCode()).isNull();
    assertThat(decoded.effectiveDate()).isNull();
    assertThat(decoded.totalPremium()).isEqualByComparingTo("0").hasScaleOf(2);
    assertThat(decoded.renewalCount()).isZero();
  }

  @Test
  void lastUpdatedKeepsOnlyTheDate() {
    Policy base = policy(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    Policy policy =
        new Policy(
            base.policyNumber(),
            base.policyTypeCode(),
            base.policyStatusCode(),
            base.effectiveDate(),
            base.expiryDate(),
            base.policyholderId(),
            base.agentCode(),
            base.branchCode(),
            base.totalPremium(),
            base.deductible(),
            base.coverageLimit(),
            base.inceptionDate(),
            base.renewalCount(),
            base.uwStatusCode(),
            base.riskScore(),
            base.webIndicator(),
            base.apiFlag(),
            LocalDateTime.of(2024, 3, 1, 14, 30, 5),
            base.updatedBy());

    Policy decoded = new PolicyRecordCodec().decode(new PolicyRecordCodec().encode(policy));

    assertThat(decoded.lastUpdated()).isEqualTo(LocalDateTime.of(2024, 3, 1, 0, 0));
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void coverageRoundTrips(Charset charset) {
    Coverage coverage =
        new Coverage(
            "POL-00012345",
            999,
            "XXXX",
            "Out-of-domain coverage type kept (Q-35)",
            new BigDecimal("-99999999999.99"),
            new BigDecimal("9999999.99"),
            new BigDecimal("0.01"),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(9999, 12, 31),
            "AC",
            100,
            "T00001",
            "C1234");
    CoverageRecordCodec codec = new CoverageRecordCodec(charset);

    byte[] bytes = codec.encode(coverage);

    assertThat(bytes).hasSize(124);
    assertThat(codec.decode(bytes)).isEqualTo(coverage);
    assertThat(codec.decode(bytes).coverageType()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void premiumRoundTripsFourDecimalFactors(Charset charset) {
    Premium premium =
        new Premium(
            "POL-00012345",
            1,
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 12, 31),
            new BigDecimal("9999999.9999"),
            new BigDecimal("999.9999"),
            new BigDecimal("0.0001"),
            new BigDecimal("-999.9999"),
            new BigDecimal("1.0000"),
            new BigDecimal("-999.99"),
            new BigDecimal("9999999.99"),
            new BigDecimal("0.00"),
            new BigDecimal("999999999.99"),
            "MO",
            new BigDecimal("-0.01"),
            LocalDate.of(2024, 1, 2),
            "PREMBAT");
    PremiumRecordCodec codec = new PremiumRecordCodec(charset);

    byte[] bytes = codec.encode(premium);

    assertThat(bytes).hasSize(105);
    assertThat(codec.decode(bytes)).isEqualTo(premium);
    assertThat(codec.decode(bytes).classFactor()).hasScaleOf(4);
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void customerRoundTrips(Charset charset) {
    PolicyHolder holder =
        new PolicyHolder(
            "CUST000001",
            "I",
            "Testperson",
            "Synthetic",
            "Q",
            null,
            "1 Example Street",
            null,
            "Springfield",
            "IL",
            "62701",
            "USA",
            "555-0100",
            "synthetic@example.invalid",
            LocalDate.of(1970, 1, 1),
            "0000",
            "00-0000000",
            850,
            "A",
            "Y",
            LocalDate.of(2020, 5, 17),
            LocalDateTime.of(2024, 1, 1, 0, 0));
    CustomerRecordCodec codec = new CustomerRecordCodec(charset);

    byte[] bytes = codec.encode(holder);

    assertThat(bytes).hasSize(365);
    assertThat(codec.decode(bytes)).isEqualTo(holder);
    assertThat(codec.encode(codec.decode(bytes))).isEqualTo(bytes);
  }

  @Test
  void invalidDatesAndDigitsAreRejectedWithoutEchoingTheData() {
    PolicyRecordCodec codec = new PolicyRecordCodec(StandardCharsets.ISO_8859_1);
    byte[] bytes = codec.encode(policy(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
    byte[] badDate = bytes.clone();
    System.arraycopy("20230229".getBytes(StandardCharsets.ISO_8859_1), 0, badDate, 17, 8);
    byte[] badDigits = bytes.clone();
    badDigits[79] = 'A';

    assertThatThrownBy(() -> codec.decode(badDate))
        .isInstanceOf(CopybookDataException.class)
        .hasMessage("POLICY-RECORD.POLICY-EFFECTIVE-DATE is not a valid YYYYMMDD date");
    assertThatThrownBy(() -> codec.decode(badDigits))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("POLICY-RENEWAL-COUNT is not numeric");
    assertThatThrownBy(() -> codec.decode(new byte[124]))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("125 bytes");
  }

  @Test
  void valuesWiderThanTheFieldAreRejected() {
    CustomerRecordCodec codec = new CustomerRecordCodec();
    PolicyHolder holder =
        new PolicyHolder(
            "C1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "n\u00e4me\u2603@example.invalid",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> codec.encode(holder))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("CUST-EMAIL")
        .hasMessageNotContaining("example.invalid");
  }
}
