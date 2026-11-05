package com.acme.insurance.pas.batch.migration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns a row read from either database into the same canonical text, so DB2 and PostgreSQL rows
 * with the same business content get the same SHA-256. CHAR padding is ignored, decimals keep the
 * column scale ({@code 1.50} and {@code 1.5} differ, as a DECIMAL(p,s) column never returns the
 * latter), dates and timestamps are ISO-8601. Hashes are only compared in memory and never stored.
 */
public final class RowCanonicalizer {

  private static final char FIELD = '\u001f';
  private static final String NULL = "\u0000";

  private RowCanonicalizer() {}

  public static String canonical(Object value) {
    if (value == null) {
      return NULL;
    }
    if (value instanceof String s) {
      return s.stripTrailing();
    }
    if (value instanceof BigDecimal d) {
      return d.toPlainString();
    }
    if (value instanceof Date d) {
      return d.toLocalDate().toString();
    }
    if (value instanceof Timestamp t) {
      return t.toLocalDateTime().toString();
    }
    if (value instanceof Number n) {
      return Long.toString(n.longValue());
    }
    return value.toString();
  }

  /** The record key as shown in reports: canonical key values joined with '|'. */
  public static String key(List<Object> keyValues) {
    StringBuilder out = new StringBuilder();
    for (Object v : keyValues) {
      if (!out.isEmpty()) {
        out.append('|');
      }
      out.append(v == null ? "" : canonical(v));
    }
    return out.toString();
  }

  public static String hash(Object[] row) {
    StringBuilder text = new StringBuilder();
    for (Object v : row) {
      text.append(canonical(v)).append(FIELD);
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
