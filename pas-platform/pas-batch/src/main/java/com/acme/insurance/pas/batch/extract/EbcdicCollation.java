package com.acme.insurance.pas.batch.extract;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Reproduces DB2 for z/OS {@code ORDER BY} on character columns (EBCDIC CCSID 37 byte order: lower
 * case &lt; upper case &lt; digits) on PostgreSQL. Each column is wrapped in {@code translate(col,
 * :ebcdicFrom, :ebcdicTo) COLLATE "C"}, which maps the character with EBCDIC code {@code b} to
 * {@code U+0100 + b}; with the {@code C} collation those sort in EBCDIC byte order.
 */
public final class EbcdicCollation {

  public static final String FROM_PARAM = "ebcdicFrom";
  public static final String TO_PARAM = "ebcdicTo";

  private static final String FROM;
  private static final String TO;

  static {
    Charset cp037 = Charset.forName("IBM037");
    StringBuilder from = new StringBuilder();
    StringBuilder to = new StringBuilder();
    // EBCDIC 0x00 decodes to NUL, which PostgreSQL text cannot hold, so start at 0x01.
    for (int b = 1; b < 256; b++) {
      from.append(new String(new byte[] {(byte) b}, cp037));
      to.append((char) (0x100 + b));
    }
    FROM = from.toString();
    TO = to.toString();
  }

  private EbcdicCollation() {}

  /** SQL sort key for a character column, ascending with NULLs last (DB2 sorts NULL high). */
  public static String key(String column) {
    return "translate(" + column + ", :" + FROM_PARAM + ", :" + TO_PARAM + ") COLLATE \"C\"";
  }

  public static Map<String, Object> parameters() {
    return Map.of(FROM_PARAM, FROM, TO_PARAM, TO);
  }

  /** Replaces each {@code %s} in {@code template}, in order, with the given SQL sort keys. */
  public static String sortKeys(String template, String... keys) {
    StringBuilder sql = new StringBuilder();
    int from = 0;
    for (String sortKey : keys) {
      int at = template.indexOf("%s", from);
      if (at < 0) {
        throw new IllegalArgumentException("more sort keys than %s placeholders");
      }
      sql.append(template, from, at).append(sortKey);
      from = at + 2;
    }
    if (template.indexOf("%s", from) >= 0) {
      throw new IllegalArgumentException("fewer sort keys than %s placeholders");
    }
    return sql.append(template.substring(from)).toString();
  }
}
