package com.acme.insurance.pas.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.InstallmentCode;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.Premium;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RecordCodecTest {
  private static final LocalDate EFFECTIVE = LocalDate.of(2024, 2, 29);
  private static final OffsetDateTime UPDATED =
      OffsetDateTime.of(2024, 2, 29, 0, 0, 0, 0, ZoneOffset.UTC);

  @ParameterizedTest
  @MethodSource("charsets")
  void policyRoundTripsInBothCharsets(CopybookCharset charset) {
    PolicyRecordCodec records = new PolicyRecordCodec(charset);
    Policy policy = policy();
    byte[] encoded = records.encode(policy);

    assertThat(encoded).hasSize(125);
    assertThat(records.decode(encoded)).isEqualTo(policy);
    assertThat(records.encode(records.decode(encoded))).containsExactly(encoded);
  }

  @Test
  void ebcdicPolicyBytesMatchHandCraftedCanonicalHex() {
    byte[] encoded = new PolicyRecordCodec(CopybookCharset.EBCDIC).encode(policy());

    assertThat(encoded).startsWith(HexFormat.of().parseHex("D7D6D3F0F0F0F1F0F0F0F0F0"));
    assertThat(encoded).containsSequence(HexFormat.of().parseHex("00000123456C"));
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void coverageRoundTripsInBothCharsets(CopybookCharset charset) {
    CoverageRecordCodec records = new CoverageRecordCodec(charset);
    Coverage coverage =
        new Coverage(
            "POL000100000",
            1,
            Coded.of(CoverageType.PROPERTY),
            "Home property",
            new BigDecimal("12345678901.23"),
            new BigDecimal("1234567.89"),
            new BigDecimal("123456789.01"),
            EFFECTIVE,
            LocalDate.of(2025, 2, 28),
            Coded.of(CoverageStatus.ACTIVE),
            100,
            "NY0001",
            "FRM01");
    byte[] encoded = records.encode(coverage);

    assertThat(encoded).hasSize(124);
    assertThat(records.decode(encoded)).isEqualTo(coverage);
    assertThat(records.encode(records.decode(encoded))).containsExactly(encoded);
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void customerRoundTripsInBothCharsets(CopybookCharset charset) {
    CustomerRecordCodec records = new CustomerRecordCodec(charset);
    PolicyHolder customer =
        new PolicyHolder(
            "CUST000001",
            Coded.of(CustomerType.INDIVIDUAL),
            "Example",
            "Jane",
            "",
            "",
            "1 Main Street",
            "",
            "Albany",
            "NY",
            "10001",
            "USA",
            "5551234567",
            "jane@example.test",
            LocalDate.of(1980, 4, 17),
            "4831",
            "987654320",
            713,
            Coded.of(RiskTier.STANDARD),
            Coded.of(YesNo.NO),
            LocalDate.of(2010, 1, 1),
            UPDATED);
    byte[] encoded = records.encode(customer);

    assertThat(encoded).hasSize(365);
    assertThat(records.decode(encoded)).isEqualTo(customer);
    assertThat(records.encode(records.decode(encoded))).containsExactly(encoded);
  }

  @ParameterizedTest
  @MethodSource("charsets")
  void premiumRoundTripsInBothCharsets(CopybookCharset charset) {
    PremiumRecordCodec records = new PremiumRecordCodec(charset);
    Premium premium =
        new Premium(
            "POL000100000",
            2,
            EFFECTIVE,
            LocalDate.of(2025, 2, 28),
            new BigDecimal("1234567.8901"),
            new BigDecimal("1.2345"),
            new BigDecimal("1.1111"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("12.34"),
            new BigDecimal("1234567.89"),
            new BigDecimal("1234567.89"),
            new BigDecimal("123456789.01"),
            Coded.of(InstallmentCode.ANNUAL),
            new BigDecimal("1234567.89"),
            EFFECTIVE,
            "SYSTEM");
    byte[] encoded = records.encode(premium);

    assertThat(encoded).hasSize(105);
    assertThat(records.decode(encoded)).isEqualTo(premium);
    assertThat(records.encode(records.decode(encoded))).containsExactly(encoded);
  }

  @Test
  void unknownCodesArePreservedAndKnownEnumsResolve() {
    assertThat(Coded.of(PolicyType.class, " CGL ").raw()).isEqualTo("CGL");
    assertThat(Coded.of(PolicyType.class, "CGL").isKnown()).isFalse();
    assertThat(Coded.of(PolicyType.class, "AUT").known()).contains(PolicyType.AUTO);
    assertThat(Coded.of(UnderwritingStatus.class, "RS").known())
        .contains(UnderwritingStatus.REVIEW_REQUIRED);
  }

  @Test
  void policyRecordPreservesUnknownCodesAndLegacyThreeDigitModuloMoves() {
    Policy source =
        new Policy(
            policy().policyNumber(),
            Coded.of(PolicyType.class, "CGL"),
            Coded.of(PolicyStatus.class, "XX"),
            policy().effectiveDate(),
            policy().expiryDate(),
            policy().policyholderId(),
            policy().agentCode(),
            policy().branchCode(),
            policy().totalPremium(),
            policy().deductible(),
            policy().coverageLimit(),
            policy().inceptionDate(),
            1000,
            Coded.of(UnderwritingStatus.class, "QX"),
            1050,
            policy().webIndicator(),
            policy().apiFlag(),
            policy().lastUpdated(),
            policy().updatedBy());
    Policy decoded =
        new PolicyRecordCodec(CopybookCharset.ASCII)
            .decode(new PolicyRecordCodec(CopybookCharset.ASCII).encode(source));

    assertThat(decoded.policyType().raw()).isEqualTo("CGL");
    assertThat(decoded.policyStatus().raw()).isEqualTo("XX");
    assertThat(decoded.uwStatus().raw()).isEqualTo("QX");
    assertThat(decoded.renewalCount()).isZero();
    assertThat(decoded.riskScore()).isEqualTo(50);
  }

  @Test
  void rejectsWrongRecordLengthAndDoesNotExposeInvalidInput() {
    assertThatThrownBy(
            () ->
                new CopybookCodec(PasCopybooks.POLICY_RECORD, CopybookCharset.ASCII)
                    .reader(new byte[4]))
        .isInstanceOf(CopybookDataException.class);
    RecordLayout customerLayout = RecordLayout.builder("CUSTOMER-RECORD").date("DOB").build(8);
    byte[] invalid = "19801340".getBytes(CopybookCharset.ASCII.charset());
    assertThatThrownBy(
            () ->
                new CopybookCodec(customerLayout, CopybookCharset.ASCII)
                    .reader(invalid)
                    .date("DOB"))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageNotContaining("19801340");
  }

  @Test
  void datesHandleSentinelNullAndStrictCalendarValues() {
    RecordLayout layout = RecordLayout.builder("DATE-RECORD").date("DATE").build(8);
    for (CopybookCharset charset : CopybookCharset.values()) {
      CopybookCodec codec = new CopybookCodec(layout, charset);
      assertThat(codec.reader(codec.writer().putDate("DATE", null).toByteArray()).date("DATE"))
          .isNull();
      assertThat(
              codec
                  .reader(codec.writer().putDate("DATE", LocalDate.MAX).toByteArray())
                  .date("DATE"))
          .isEqualTo(LocalDate.MAX);
      assertThat(codec.reader(codec.writer().putDate("DATE", EFFECTIVE).toByteArray()).date("DATE"))
          .isEqualTo(EFFECTIVE);
      byte[] spaces = "        ".getBytes(charset.charset());
      assertThat(codec.reader(spaces).date("DATE")).isNull();
      byte[] invalid = "20230229".getBytes(charset.charset());
      assertThatThrownBy(() -> codec.reader(invalid).date("DATE"))
          .isInstanceOf(CopybookDataException.class);
    }
  }

  @Test
  void packedDecimalSupportsSignsPadNibblesAndOverflowPolicies() {
    RecordLayout oneDigit = RecordLayout.builder("PACKED").packed("VALUE", 1, 0, true).build(1);
    CopybookCodec oneDigitCodec = new CopybookCodec(oneDigit, CopybookCharset.ASCII);
    for (int sign : new int[] {0xc, 0xd, 0xf, 0xa, 0xb, 0xe}) {
      assertThat(oneDigitCodec.reader(new byte[] {(byte) sign}).decimal("VALUE"))
          .isEqualTo(BigDecimal.ZERO.setScale(0));
    }
    assertThatThrownBy(() -> oneDigitCodec.reader(new byte[] {(byte) 0xac}).decimal("VALUE"))
        .isInstanceOf(CopybookDataException.class);
    assertThatThrownBy(() -> oneDigitCodec.reader(new byte[] {(byte) 0x05}).decimal("VALUE"))
        .isInstanceOf(CopybookDataException.class);

    RecordLayout even = RecordLayout.builder("EVEN").packed("VALUE", 6, 2, true).build(4);
    CopybookCodec evenCodec = new CopybookCodec(even, CopybookCharset.ASCII);
    byte[] evenBytes =
        evenCodec.writer().putDecimal("VALUE", new BigDecimal("12.34")).toByteArray();
    assertThat(evenBytes[0] >>> 4).isZero();
    assertThat(evenCodec.reader(evenBytes).decimal("VALUE")).isEqualTo(new BigDecimal("12.34"));

    RecordLayout layout = RecordLayout.builder("OVERFLOW").packed("VALUE", 5, 2, true).build(3);
    CopybookCodec codec = new CopybookCodec(layout, CopybookCharset.ASCII);
    assertThatThrownBy(() -> codec.writer().putDecimal("VALUE", new BigDecimal("1234.56")))
        .isInstanceOf(CopybookDataException.class);
    byte[] truncated =
        codec
            .writer()
            .putDecimal("VALUE", new BigDecimal("1234.56"), OverflowPolicy.TRUNCATE)
            .toByteArray();
    assertThat(codec.reader(truncated).decimal("VALUE")).isEqualTo(new BigDecimal("234.56"));
    assertThat(codec.reader(new byte[] {0, 0, 0x0d}).decimal("VALUE"))
        .isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void signedDisplayOverpunchesAndUnsignedDisplayRoundTrip() {
    RecordLayout layout =
        RecordLayout.builder("DISPLAY")
            .signedDisplay("SIGNED", 3, 1)
            .display("UNSIGNED", 3)
            .build(6);
    for (CopybookCharset charset : CopybookCharset.values()) {
      CopybookCodec codec = new CopybookCodec(layout, charset);
      byte[] encoded =
          codec
              .writer()
              .putDecimal("SIGNED", new BigDecimal("-12.3"))
              .putInteger("UNSIGNED", 456)
              .toByteArray();
      assertThat(codec.reader(encoded).decimal("SIGNED")).isEqualTo(new BigDecimal("-12.3"));
      assertThat(codec.reader(encoded).integer("UNSIGNED")).isEqualTo(456);
    }
  }

  @Test
  void numericEncodingAndDecodingIgnoreTheDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"));
      RecordLayout layout =
          RecordLayout.builder("LOCALE")
              .signedDisplay("DISPLAY", 4, 2)
              .packed("PACKED", 4, 2, true)
              .build(7);
      CopybookCodec codec = new CopybookCodec(layout, CopybookCharset.ASCII);
      byte[] encoded =
          codec
              .writer()
              .putDecimal("DISPLAY", new BigDecimal("-12.34"))
              .putDecimal("PACKED", new BigDecimal("98.76"))
              .toByteArray();

      assertThat(codec.reader(encoded).decimal("DISPLAY")).isEqualTo(new BigDecimal("-12.34"));
      assertThat(codec.reader(encoded).decimal("PACKED")).isEqualTo(new BigDecimal("98.76"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void rejectsNonDecimalNibbleInEbcdicSignedDisplay() {
    RecordLayout layout =
        RecordLayout.builder("SIGNED-DISPLAY").signedDisplay("VALUE", 1, 0).build(1);

    assertThatThrownBy(
            () ->
                new CopybookCodec(layout, CopybookCharset.EBCDIC)
                    .reader(new byte[] {(byte) 0xca})
                    .decimal("VALUE"))
        .isInstanceOf(CopybookDataException.class);
  }

  @Test
  void rejectsOverflowAndAcceptsTruncationForAlphanumericFields() {
    RecordLayout layout = RecordLayout.builder("TEXT").x("TEXT", 3).build(3);
    CopybookCodec codec = new CopybookCodec(layout, CopybookCharset.ASCII);
    assertThatThrownBy(() -> codec.writer().putString("TEXT", "long"))
        .isInstanceOf(CopybookDataException.class);
    assertThat(
            codec
                .reader(
                    codec.writer().putString("TEXT", "long", OverflowPolicy.TRUNCATE).toByteArray())
                .string("TEXT"))
        .isEqualTo("lon");
  }

  @Test
  void rejectsCharactersOutsideTheSelectedCodePageWithoutLeakingValues() {
    RecordLayout layout = RecordLayout.builder("TEXT").x("TEXT", 3).build(3);
    CopybookCodec codec = new CopybookCodec(layout, CopybookCharset.EBCDIC);

    assertThatThrownBy(() -> codec.writer().putString("TEXT", "🚀"))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageNotContaining("🚀");
  }

  private static Stream<CopybookCharset> charsets() {
    return Stream.of(CopybookCharset.EBCDIC, CopybookCharset.ASCII);
  }

  private static Policy policy() {
    return new Policy(
        "POL000100000",
        Coded.of(PolicyType.AUTO),
        Coded.of(PolicyStatus.PENDING),
        EFFECTIVE,
        LocalDate.of(2025, 2, 28),
        "CUST000001",
        "AGT001",
        "NY01",
        new BigDecimal("1234.56"),
        new BigDecimal("12.34"),
        new BigDecimal("12345678901.23"),
        EFFECTIVE,
        12,
        Coded.of(UnderwritingStatus.REVIEW_REQUIRED),
        123,
        Coded.of(YesNo.YES),
        Coded.of(YesNo.NO),
        UPDATED,
        "SYSTEM");
  }
}
