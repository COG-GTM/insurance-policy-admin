package com.acme.insurance.pas.batch.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExposureCsvTest {

  @Test
  void headerIsPaddedToLrecl() {
    byte[] header = ExposureCsv.header();
    assertThat(header).hasSize(300);
    assertThat(new String(header, StandardCharsets.ISO_8859_1).stripTrailing())
        .isEqualTo(
            "POLICY_TYPE,BRANCH_CODE,COVERAGE_TYPE,RATING_TERRITORY,CLASS_CODE,"
                + "POLICY_COUNT,TOTAL_EXPOSURE,TOTAL_PREMIUM,TOTAL_DEDUCTIBLE");
  }

  @Test
  void fieldsAndAmounts() {
    assertThat(ExposureCsv.field(null)).isEmpty();
    assertThat(ExposureCsv.field("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
    assertThat(ExposureCsv.amount(new BigDecimal("-12.50"))).isEqualTo("-12.50");
    assertThat(ExposureCsv.amount(new BigDecimal("0"))).isEqualTo("0.00");
    assertThat(ExposureCsv.amount(null)).isEmpty();
  }

  @Test
  void overlongRecordIsRejected() {
    assertThatThrownBy(() -> ExposureCsv.record("x".repeat(301)))
        .isInstanceOf(IllegalStateException.class);
  }
}
