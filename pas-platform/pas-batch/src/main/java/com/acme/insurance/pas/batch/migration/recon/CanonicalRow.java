package com.acme.insurance.pas.batch.migration.recon;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

public final class CanonicalRow {
  private static final char FIELD_SEPARATOR = '\u001f';
  private static final DateTimeFormatter UTC_MICROS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private CanonicalRow() {}

  public static String canonicalForm(
      String tableName, List<String> businessColumns, Map<String, Object> row, ZoneId sourceZone) {
    StringBuilder canonical = new StringBuilder(tableName);
    for (String column : businessColumns) {
      canonical
          .append(FIELD_SEPARATOR)
          .append(canonicalValue(row.get(column.toLowerCase(Locale.ROOT)), sourceZone));
    }
    return canonical.toString();
  }

  public static String sha256(
      String tableName, List<String> businessColumns, Map<String, Object> row, ZoneId sourceZone) {
    return hex(digest("SHA-256", canonicalForm(tableName, businessColumns, row, sourceZone)));
  }

  public static String hmacSha256(
      String tableName,
      List<String> businessColumns,
      Map<String, Object> row,
      ZoneId sourceZone,
      SecretKey key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  canonicalForm(tableName, businessColumns, row, sourceZone)
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  private static String canonicalValue(Object value, ZoneId sourceZone) {
    if (value == null) {
      return "\\N";
    }
    if (value instanceof String text) {
      int end = text.length();
      while (end > 0 && text.charAt(end - 1) == ' ') {
        end--;
      }
      return text.substring(0, end);
    }
    if (value instanceof java.math.BigDecimal decimal) {
      java.math.BigDecimal normalized = decimal.stripTrailingZeros();
      return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }
    if (value instanceof LocalDate date) {
      return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    if (value instanceof LocalDateTime timestamp) {
      return UTC_MICROS.format(
          timestamp.atZone(sourceZone).toInstant().truncatedTo(ChronoUnit.MICROS));
    }
    if (value instanceof OffsetDateTime timestamp) {
      return UTC_MICROS.format(timestamp.toInstant().truncatedTo(ChronoUnit.MICROS));
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return UTC_MICROS.format(timestamp.toInstant().truncatedTo(ChronoUnit.MICROS));
    }
    if (value instanceof Instant timestamp) {
      return UTC_MICROS.format(timestamp.truncatedTo(ChronoUnit.MICROS));
    }
    if (value instanceof Short || value instanceof Integer || value instanceof Long) {
      return value.toString();
    }
    if (value instanceof Number number) {
      return number.toString();
    }
    return value.toString();
  }

  private static byte[] digest(String algorithm, String value) {
    try {
      return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
