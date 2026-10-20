package com.acme.insurance.pas.batch.exposure;

import static com.acme.insurance.pas.batch.extract.ExtractOutputs.RECORD_CHARSET;
import static com.acme.insurance.pas.batch.extract.ExtractOutputs.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MONTHLY-EXPOSURE record format: one CSV line per record inside the FB LRECL=300 dataset, blank
 * padded to 300 bytes. First record is the column-name header. Character values are unpadded, NULL
 * is an empty field, amounts are plain decimals with 2 places and a leading {@code -}. See {@code
 * docs/modernization/extract-layouts.md}.
 */
public final class ExposureCsv {

  public static final int LRECL = 300;

  public static final List<String> COLUMNS =
      List.of(
          "POLICY_TYPE",
          "BRANCH_CODE",
          "COVERAGE_TYPE",
          "RATING_TERRITORY",
          "CLASS_CODE",
          "POLICY_COUNT",
          "TOTAL_EXPOSURE",
          "TOTAL_PREMIUM",
          "TOTAL_DEDUCTIBLE");

  private ExposureCsv() {}

  public static byte[] header() {
    return record(COLUMNS.toArray(String[]::new));
  }

  public static byte[] row(ResultSet rs) throws SQLException {
    return record(
        field(text(rs, "policy_type")),
        field(text(rs, "branch_code")),
        field(text(rs, "coverage_type")),
        field(text(rs, "rating_territory")),
        field(text(rs, "class_code")),
        Long.toString(rs.getLong("policy_count")),
        amount(rs.getBigDecimal("total_exposure")),
        amount(rs.getBigDecimal("total_premium")),
        amount(rs.getBigDecimal("total_deductible")));
  }

  static String field(String value) {
    if (value == null) {
      return "";
    }
    if (value.chars().anyMatch(c -> c == ',' || c == '"' || c == '\r' || c == '\n')) {
      return '"' + value.replace("\"", "\"\"") + '"';
    }
    return value;
  }

  static String amount(BigDecimal value) {
    return value == null ? "" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  static byte[] record(String... fields) {
    String line = Arrays.stream(fields).collect(Collectors.joining(","));
    byte[] bytes = line.getBytes(RECORD_CHARSET);
    if (bytes.length > LRECL) {
      throw new IllegalStateException("exposure record exceeds LRECL " + LRECL + ": " + line);
    }
    byte[] record = new byte[LRECL];
    Arrays.fill(record, (byte) ' ');
    System.arraycopy(bytes, 0, record, 0, bytes.length);
    return record;
  }
}
