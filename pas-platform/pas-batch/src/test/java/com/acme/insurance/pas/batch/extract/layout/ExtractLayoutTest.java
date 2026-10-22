package com.acme.insurance.pas.batch.extract.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.copybook.CopybookDataException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ExtractLayoutTest {
  @Test
  void pinsFixedLayoutLengthsAndFieldOffsets() {
    assertThat(ExtractLayouts.DAILY_POLICY.recordLayout().length()).isEqualTo(200);
    assertThat(
            ExtractLayouts.DAILY_POLICY.recordLayout().fields().stream()
                .map(field -> field.offset())
                .toList())
        .containsExactly(0, 12, 15, 17, 25, 33, 43, 49, 53, 64, 73, 86, 88, 93);
    assertThat(ExtractLayouts.DAILY_COVERAGE.recordLayout().length()).isEqualTo(150);
    assertThat(
            ExtractLayouts.DAILY_COVERAGE.recordLayout().fields().stream()
                .map(field -> field.offset())
                .toList())
        .containsExactly(0, 12, 17, 21, 61, 74, 83, 94, 102, 110, 112);
  }

  @Test
  void encodesPolicyNullsNegativeOverpunchAndLatinOne() {
    Map<String, Object> row = policyRow();
    row.put("AGENT_CODE", null);
    row.put("TOTAL_PREMIUM", new BigDecimal("-12.34"));
    row.put("EFFECTIVE_DATE", LocalDate.of(2025, 2, 9));

    byte[] record = ExtractRecordEncoder.encodeFixed(ExtractLayouts.DAILY_POLICY, row);

    assertThat(record).hasSize(200);
    assertThat(new String(record, 43, 6, StandardCharsets.ISO_8859_1)).isEqualTo("      ");
    assertThat(new String(record, 53, 11, StandardCharsets.ISO_8859_1)).isEqualTo("0000000123M");
    assertThat(new String(record, 17, 8, StandardCharsets.ISO_8859_1)).isEqualTo("20250209");
    row.put("POLICY_TYPE", "ÉÉÉ");
    assertThat(ExtractRecordEncoder.encodeFixed(ExtractLayouts.DAILY_POLICY, row)[12] & 0xff)
        .isEqualTo(0xC9);
  }

  @Test
  void rejectsAlphanumericAndNumericOverflow() {
    Map<String, Object> oversizedPolicyNumber = policyRow();
    oversizedPolicyNumber.put("POLICY_NUMBER", "1234567890123");
    assertThatThrownBy(
            () ->
                ExtractRecordEncoder.encodeFixed(
                    ExtractLayouts.DAILY_POLICY, oversizedPolicyNumber))
        .isInstanceOf(CopybookDataException.class);

    Map<String, Object> overflow = policyRow();
    overflow.put("TOTAL_PREMIUM", new BigDecimal("1000000000.00"));
    assertThatThrownBy(
            () -> ExtractRecordEncoder.encodeFixed(ExtractLayouts.DAILY_POLICY, overflow))
        .isInstanceOf(CopybookDataException.class);
  }

  @Test
  void formatsCsvWithNullsQuotingAndFixedScale() {
    Map<String, Object> row = new HashMap<>();
    row.put("POLICY_TYPE", "HO,ME");
    row.put("BRANCH_CODE", null);
    row.put("COVERAGE_TYPE", "FIRE");
    row.put("RATING_TERRITORY", "Q\"1");
    row.put("CLASS_CODE", "A");
    row.put("POLICY_COUNT", 2L);
    row.put("TOTAL_EXPOSURE", new BigDecimal("12.3"));
    row.put("TOTAL_PREMIUM", new BigDecimal("1.00"));
    row.put("TOTAL_DEDUCTIBLE", new BigDecimal("-0.25"));

    assertThat(ExtractRecordEncoder.encodeCsv(row))
        .isEqualTo("\"HO,ME\",,FIRE,\"Q\"\"1\",A,2,12.30,1.00,-0.25");
  }

  @Test
  void rejectsCsvRecordsOverThreeHundredBytes() {
    Map<String, Object> row = new HashMap<>();
    row.put("POLICY_TYPE", "x".repeat(301));
    assertThatThrownBy(() -> ExtractRecordEncoder.encodeCsv(row))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximum byte length");
  }

  @Test
  void committedPolicyGoldenHasExpectedFieldsAtDeclaredOffsets() throws Exception {
    byte[] golden =
        new ClassPathResource("extract/golden/daily/policy_extract.dat")
            .getInputStream()
            .readAllBytes();

    assertThat(golden).hasSize(404);
    assertThat(new String(golden, 0, 12, StandardCharsets.ISO_8859_1)).isEqualTo("AB0000000001");
    assertThat(new String(golden, 53, 11, StandardCharsets.ISO_8859_1)).isEqualTo("0000000123M");
    assertThat(new String(golden, 200, 2, StandardCharsets.ISO_8859_1)).isEqualTo("\r\n");
  }

  private static Map<String, Object> policyRow() {
    Map<String, Object> row = new HashMap<>();
    row.put("POLICY_NUMBER", "AB0000000001");
    row.put("POLICY_TYPE", "HOM");
    row.put("POLICY_STATUS", "AC");
    row.put("EFFECTIVE_DATE", LocalDate.of(2025, 1, 1));
    row.put("EXPIRY_DATE", LocalDate.of(2026, 1, 1));
    row.put("POLICYHOLDER_ID", "H000000001");
    row.put("AGENT_CODE", "A00001");
    row.put("BRANCH_CODE", "B001");
    row.put("TOTAL_PREMIUM", new BigDecimal("1.00"));
    row.put("DEDUCTIBLE", new BigDecimal("0.00"));
    row.put("COVERAGE_LIMIT", new BigDecimal("1000.00"));
    row.put("UW_STATUS", "AP");
    row.put("RISK_SCORE", 7);
    return row;
  }
}
