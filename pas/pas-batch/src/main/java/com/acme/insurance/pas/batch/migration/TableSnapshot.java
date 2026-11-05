package com.acme.insurance.pas.batch.migration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads one bucket of a table: every row whose first key column ends in the bucket characters. A
 * bucket is held in memory so both sides can be compared by key without relying on ORDER BY, whose
 * collation differs between DB2 (EBCDIC) and PostgreSQL. The SQL is portable (RIGHT and RTRIM exist
 * on both databases).
 */
public final class TableSnapshot {

  /** A row with its values in {@link MigrationTable#columns()} order and its canonical hash. */
  public record Row(Object[] values, String hash) {}

  private final Map<String, Row> rows;
  private final Set<String> duplicateKeys;

  private TableSnapshot(Map<String, Row> rows, Set<String> duplicateKeys) {
    this.rows = rows;
    this.duplicateKeys = duplicateKeys;
  }

  public Map<String, Row> rows() {
    return rows;
  }

  /** Keys seen more than once; only possible on PostgreSQL UNDERWRITING_DECISIONS (Q-18). */
  public Set<String> duplicateKeys() {
    return duplicateKeys;
  }

  /** Distinct bucket values present in the table; a single empty bucket when digits is 0. */
  public static Set<String> buckets(JdbcClient jdbc, MigrationTable table, int digits) {
    Set<String> buckets = new TreeSet<>();
    if (digits == 0) {
      buckets.add("");
      return buckets;
    }
    String sql =
        "SELECT DISTINCT RIGHT(RTRIM(%s), %d) AS BUCKET FROM %s"
            .formatted(table.bucketColumn(), digits, table.qualifiedName());
    jdbc.sql(sql).query((rs, n) -> rs.getString(1)).list().stream()
        .map(b -> b == null ? "" : b)
        .forEach(buckets::add);
    return buckets;
  }

  public static TableSnapshot read(
      JdbcClient jdbc, MigrationTable table, int digits, String bucket) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(String.join(", ", table.columns()))
            .append(" FROM ")
            .append(table.qualifiedName());
    Map<String, Row> rows = new HashMap<>();
    Set<String> duplicates = new LinkedHashSet<>();
    int columns = table.columns().size();
    List<Integer> keyIndexes =
        table.keyColumns().stream().map(k -> table.columns().indexOf(k)).toList();
    org.springframework.jdbc.core.RowCallbackHandler handler =
        rs -> {
          Object[] values = values(rs, columns);
          List<Object> key = new ArrayList<>(keyIndexes.size());
          keyIndexes.forEach(i -> key.add(values[i]));
          String k = RowCanonicalizer.key(key);
          if (rows.putIfAbsent(k, new Row(values, RowCanonicalizer.hash(values))) != null) {
            duplicates.add(k);
          }
        };
    if (digits == 0) {
      jdbc.sql(sql.toString()).query(handler);
    } else {
      sql.append(" WHERE RIGHT(RTRIM(")
          .append(table.bucketColumn())
          .append("), ")
          .append(digits)
          .append(") = ?");
      jdbc.sql(sql.toString()).param(bucket).query(handler);
    }
    return new TableSnapshot(rows, duplicates);
  }

  private static Object[] values(ResultSet rs, int columns) throws SQLException {
    Object[] values = new Object[columns];
    for (int i = 0; i < columns; i++) {
      values[i] = rs.getObject(i + 1);
    }
    return values;
  }
}
