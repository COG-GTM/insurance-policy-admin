package com.acme.insurance.pas.batch.migration.reconcile;

import com.acme.insurance.pas.batch.migration.MigrationTable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Canonical record form used for record-level hashes, identical for DB2 and PostgreSQL:
 *
 * <ul>
 *   <li>fields in DDL order ({@link MigrationTable#columns()}), joined with {@code |};
 *   <li>NULL → {@code \N}; character values right-trimmed (CHAR padding differs), {@code \} and
 *       {@code |} escaped;
 *   <li>decimals at the DDL scale ({@code 123.40}), integers without leading zeros;
 *   <li>dates ISO {@code yyyy-MM-dd}; timestamps {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS}.
 * </ul>
 *
 * The record hash is the lower-case hex SHA-256 of the UTF-8 canonical string.
 */
public final class Canonical {

  public static final String NULL = "\\N";
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

  /** DDL scale of every DECIMAL column in POLICIES, COVERAGES and PREMIUMS. */
  private static final Map<String, Integer> SCALES =
      Map.ofEntries(
          Map.entry("total_premium", 2),
          Map.entry("deductible", 2),
          Map.entry("coverage_limit", 2),
          Map.entry("premium", 2),
          Map.entry("base_rate", 4),
          Map.entry("territory_factor", 4),
          Map.entry("class_factor", 4),
          Map.entry("experience_mod", 4),
          Map.entry("schedule_mod", 4),
          Map.entry("discount_pct", 2),
          Map.entry("surcharge_amt", 2),
          Map.entry("tax_amt", 2),
          Map.entry("installment_amt", 2),
          Map.entry("premium_adjustment", 2));

  private Canonical() {}

  public static String value(String column, Object value) {
    if (value == null) {
      return NULL;
    }
    if (value instanceof BigDecimal d) {
      Integer scale = SCALES.get(column);
      return (scale == null ? d.stripTrailingZeros() : d.setScale(scale)).toPlainString();
    }
    if (value instanceof Number n) {
      return Long.toString(n.longValue());
    }
    if (value instanceof Timestamp ts) {
      return TIMESTAMP.format(ts.toLocalDateTime());
    }
    if (value instanceof LocalDateTime ldt) {
      return TIMESTAMP.format(ldt);
    }
    if (value instanceof Date date) {
      return date.toLocalDate().toString();
    }
    if (value instanceof LocalDate date) {
      return date.toString();
    }
    return value.toString().stripTrailing().replace("\\", "\\\\").replace("|", "\\|");
  }

  public static String record(List<String> columns, Map<String, Object> row) {
    StringBuilder sb = new StringBuilder();
    for (String column : columns) {
      if (!sb.isEmpty()) {
        sb.append('|');
      }
      sb.append(value(column, row.get(column)));
    }
    return sb.toString();
  }

  public static String key(MigrationTable table, Map<String, Object> row) {
    return record(table.keyColumns(), row);
  }

  public static String sha256(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
