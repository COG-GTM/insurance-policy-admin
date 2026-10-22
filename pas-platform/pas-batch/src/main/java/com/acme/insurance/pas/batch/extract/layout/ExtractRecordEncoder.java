package com.acme.insurance.pas.batch.extract.layout;

import com.acme.insurance.pas.copybook.OverflowPolicy;
import com.acme.insurance.pas.copybook.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;

public final class ExtractRecordEncoder {
  private ExtractRecordEncoder() {}

  public static byte[] encodeFixed(ExtractLayout layout, Map<String, Object> row) {
    RecordLayout recordLayout = layout.recordLayout();
    var writer = layout.codec().writer();
    for (var field : recordLayout.fields()) {
      String name = field.name();
      Object value = row.get(name);
      switch (field.type()) {
        case com.acme.insurance.pas.copybook.FieldType.Alphanumeric ignored ->
            writer.putString(
                name, trimRight(value == null ? null : value.toString()), OverflowPolicy.REJECT);
        case com.acme.insurance.pas.copybook.FieldType.DateYyyyMmDd ignored ->
            writer.putDate(name, asLocalDate(value));
        case com.acme.insurance.pas.copybook.FieldType.SignedDisplay ignored ->
            writer.putDecimal(name, asDecimal(value), OverflowPolicy.REJECT);
        default -> {
          if (!(field.type() instanceof com.acme.insurance.pas.copybook.FieldType.Filler)) {
            throw new IllegalArgumentException("Unsupported extract field");
          }
        }
      }
    }
    return writer.toByteArray();
  }

  private static String trimRight(String value) {
    if (value == null) {
      return null;
    }
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  private static LocalDate asLocalDate(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof Date date) {
      return date.toLocalDate();
    }
    throw new IllegalArgumentException("Unexpected date value type");
  }

  private static BigDecimal asDecimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.longValue());
    }
    throw new IllegalArgumentException("Unexpected numeric value type");
  }

  public static String encodeCsv(Map<String, Object> row) {
    StringBuilder line = new StringBuilder();
    for (String column : ExtractLayouts.exposureColumns()) {
      if (!line.isEmpty()) {
        line.append(',');
      }
      Object value = row.get(column);
      String text = csvValue(column, value);
      if (text.indexOf(',') >= 0
          || text.indexOf('"') >= 0
          || text.indexOf('\r') >= 0
          || text.indexOf('\n') >= 0) {
        line.append('"').append(text.replace("\"", "\"\"")).append('"');
      } else {
        line.append(text);
      }
    }
    if (line.toString().getBytes(StandardCharsets.ISO_8859_1).length
        > ExtractLayouts.MONTHLY_EXPOSURE.maximumRecordLength()) {
      throw new IllegalArgumentException("CSV record exceeds maximum byte length");
    }
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) > 255) {
        throw new IllegalArgumentException("CSV record contains an unrepresentable character");
      }
    }
    return line.toString();
  }

  private static String csvValue(String column, Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof BigDecimal decimal) {
      if (column.equals("TOTAL_EXPOSURE")
          || column.equals("TOTAL_PREMIUM")
          || column.equals("TOTAL_DEDUCTIBLE")) {
        return decimal.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
      }
      return decimal.toPlainString();
    }
    if (value instanceof Number number) {
      return number.toString();
    }
    return trimRight(value.toString());
  }
}
