package com.acme.insurance.pas.batch.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Makes one ACMEINS table on the receiving side equal to the sending side, bucket by bucket, by
 * comparing record hashes: missing rows are inserted, changed rows updated, extra rows deleted. It
 * needs no change timestamp, so it also covers the five tables without a reliable LAST_UPDATED and
 * sees deletes (ADR 0001 D3). The same code does the initial bulk load (empty receiving table), the
 * scheduled delta while DB2 is the system of record, and the reverse sync of a rollback.
 *
 * <p>Inserts and updates run parent-first ({@link Phase#UPSERT}), deletes child-first ({@link
 * Phase#DELETE}), so foreign keys hold after every step. Each phase is idempotent: a failed step is
 * simply run again. Row values are never logged.
 */
public class TableSynchronizer {

  /** The two passes of a sync. */
  public enum Phase {
    UPSERT,
    DELETE
  }

  /** Rows applied to one table by one phase. */
  public record Result(long sourceRows, long inserted, long updated, long deleted) {

    public Result plus(Result other) {
      return new Result(
          sourceRows + other.sourceRows,
          inserted + other.inserted,
          updated + other.updated,
          deleted + other.deleted);
    }
  }

  private final int digits;
  private final int batchSize;

  public TableSynchronizer(int digits, int batchSize) {
    this.digits = digits;
    this.batchSize = batchSize;
  }

  public Result sync(MigrationTable table, Phase phase, JdbcClient from, JdbcTemplate to) {
    JdbcClient toClient = JdbcClient.create(to);
    Set<String> buckets = new TreeSet<>(TableSnapshot.buckets(from, table, digits));
    buckets.addAll(TableSnapshot.buckets(toClient, table, digits));
    Result total = new Result(0, 0, 0, 0);
    for (String bucket : buckets) {
      TableSnapshot source = TableSnapshot.read(from, table, digits, bucket);
      if (!source.duplicateKeys().isEmpty()) {
        throw new IllegalStateException(
            "%s has %d duplicate keys on the sending side; resolve them before syncing (Q-18)"
                .formatted(table, source.duplicateKeys().size()));
      }
      TableSnapshot target = TableSnapshot.read(toClient, table, digits, bucket);
      total = total.plus(apply(table, phase, source, target, to));
    }
    return total;
  }

  private Result apply(
      MigrationTable table,
      Phase phase,
      TableSnapshot source,
      TableSnapshot target,
      JdbcTemplate to) {
    Map<String, TableSnapshot.Row> targetRows = target.rows();
    if (phase == Phase.DELETE) {
      List<Object[]> deletes = new ArrayList<>();
      targetRows.forEach(
          (key, row) -> {
            if (!source.rows().containsKey(key)) {
              deletes.add(keyValues(table, row.values()));
            }
          });
      batch(to, deleteSql(table), deletes);
      return new Result(source.rows().size(), 0, 0, deletes.size());
    }
    List<Object[]> inserts = new ArrayList<>();
    List<Object[]> updates = new ArrayList<>();
    List<Object[]> replaceKeys = new ArrayList<>();
    source
        .rows()
        .forEach(
            (key, row) -> {
              TableSnapshot.Row existing = targetRows.get(key);
              if (existing == null) {
                inserts.add(row.values());
              } else if (target.duplicateKeys().contains(key)) {
                replaceKeys.add(keyValues(table, row.values()));
                inserts.add(row.values());
              } else if (!existing.hash().equals(row.hash())) {
                updates.add(updateValues(table, row.values()));
              }
            });
    batch(to, deleteSql(table), replaceKeys);
    batch(to, insertSql(table), inserts);
    batch(to, updateSql(table), updates);
    return new Result(
        source.rows().size(),
        inserts.size() - replaceKeys.size(),
        updates.size() + replaceKeys.size(),
        0);
  }

  private void batch(JdbcTemplate to, String sql, List<Object[]> rows) {
    for (int from = 0; from < rows.size(); from += batchSize) {
      to.batchUpdate(sql, rows.subList(from, Math.min(rows.size(), from + batchSize)));
    }
  }

  static String insertSql(MigrationTable table) {
    return "INSERT INTO %s (%s) VALUES (%s)"
        .formatted(
            table.qualifiedName(),
            String.join(", ", table.columns()),
            String.join(", ", table.columns().stream().map(c -> "?").toList()));
  }

  static String updateSql(MigrationTable table) {
    return "UPDATE %s SET %s WHERE %s"
        .formatted(
            table.qualifiedName(),
            String.join(", ", table.valueColumns().stream().map(c -> c + " = ?").toList()),
            keyPredicate(table));
  }

  static String deleteSql(MigrationTable table) {
    return "DELETE FROM %s WHERE %s".formatted(table.qualifiedName(), keyPredicate(table));
  }

  private static String keyPredicate(MigrationTable table) {
    return String.join(" AND ", table.keyColumns().stream().map(c -> c + " = ?").toList());
  }

  private static Object[] keyValues(MigrationTable table, Object[] row) {
    return table.keyColumns().stream().map(c -> row[table.columns().indexOf(c)]).toArray();
  }

  private static Object[] updateValues(MigrationTable table, Object[] row) {
    List<Object> values = new ArrayList<>();
    table.valueColumns().forEach(c -> values.add(row[table.columns().indexOf(c)]));
    table.keyColumns().forEach(c -> values.add(row[table.columns().indexOf(c)]));
    return values.toArray();
  }
}
