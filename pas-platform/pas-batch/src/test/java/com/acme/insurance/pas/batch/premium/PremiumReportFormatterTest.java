package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PremiumReportFormatterTest {
  @Test
  void formatsFixedWidthReportAndPremiumAmounts() {
    List<String> lines =
        PremiumReportFormatter.format(
            LocalDate.of(2024, 10, 15),
            List.of(
                new PremiumReportFormatter.PremiumReportItem(
                    "POL000000401", "UPDATED", new BigDecimal("904.75"), null),
                new PremiumReportFormatter.PremiumReportItem(
                    "POL000000402", "ERROR", null, "DataIntegrityViolationException")),
            2,
            1,
            1);

    assertThat(lines)
        .contains(
            "ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: 20241015"
                + " "
                    .repeat(
                        132
                            - "ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: 20241015"
                                .length()),
            "POL000000401 PREMIUM: 000000904.75"
                + " ".repeat(132 - "POL000000401 PREMIUM: 000000904.75".length()),
            "POL000000402 ERROR: DataIntegrityViolationException"
                + " ".repeat(132 - "POL000000402 ERROR: DataIntegrityViolationException".length()),
            "TOTAL POLICIES READ:    0000002"
                + " ".repeat(132 - "TOTAL POLICIES READ:    0000002".length()),
            "TOTAL POLICIES UPDATED: 0000001"
                + " ".repeat(132 - "TOTAL POLICIES UPDATED: 0000001".length()),
            "TOTAL ERRORS:           0000001"
                + " ".repeat(132 - "TOTAL ERRORS:           0000001".length()));
    assertThat(lines.get(1)).isEqualTo("-".repeat(132));
    assertThat(lines.get(4)).isEqualTo("=".repeat(132));
    assertThat(lines).allMatch(line -> line.length() == 132);
  }

  @Test
  void rendersZeroAndMaximumNumericValuesWithoutLocaleFormatting() {
    assertThat(PremiumReportFormatter.formatPremium(BigDecimal.ZERO)).isEqualTo("000000000.00");
    assertThat(PremiumReportFormatter.formatPremium(new BigDecimal("999999999.99")))
        .isEqualTo("999999999.99");
  }
}
