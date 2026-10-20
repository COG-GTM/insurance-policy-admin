package com.acme.insurance.pas.batch.migration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Idempotent writes into {@code pas.*}: {@code INSERT … ON CONFLICT (pk) DO UPDATE}, so a bulk load
 * or delta can be re-run any number of times and converges on the source. UNDERWRITING_DECISIONS
 * has a surrogate key in the target (Q-20) and is matched on the DB2 key instead.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class TargetWriter {

  private final NamedParameterJdbcTemplate jdbc;
  private final PolicyHolderPii pii;

  public TargetWriter(NamedParameterJdbcTemplate jdbc, PolicyHolderPii pii) {
    this.jdbc = jdbc;
    this.pii = pii;
  }

  /** Writes rows already mapped by {@link SourceRows}; returns the number written. */
  public int write(MigrationTable table, List<? extends Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    List<String> columns = targetColumns(table);
    SqlParameterSource[] params = new SqlParameterSource[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      if (table == MigrationTable.POLICY_HOLDERS && row.containsKey("date_of_birth")) {
        pii.encryptInPlace(row);
      }
      MapSqlParameterSource p = new MapSqlParameterSource();
      for (String column : columns) {
        p.addValue(column, row.get(column));
      }
      params[i] = p;
    }
    if (table == MigrationTable.UNDERWRITING_DECISIONS) {
      return writeDecisions(table, columns, params);
    }
    jdbc.batchUpdate(upsertSql(table), params);
    return rows.size();
  }

  private int writeDecisions(
      MigrationTable table, List<String> columns, SqlParameterSource[] params) {
    String update =
        "UPDATE "
            + table.targetTable()
            + " SET "
            + columns.stream()
                .filter(c -> !table.keyColumns().contains(c))
                .map(c -> c + " = :" + c)
                .collect(Collectors.joining(", "))
            + " WHERE policy_number = :policy_number AND decision_date = :decision_date";
    String insert = insertSql(table, columns);
    for (SqlParameterSource p : params) {
      if (jdbc.update(update, p) == 0) {
        jdbc.update(insert, p);
      }
    }
    return params.length;
  }

  /** Target columns of a table, including the encrypted PII columns of POLICY_HOLDERS. */
  public static List<String> targetColumnsOf(MigrationTable table) {
    return List.copyOf(targetColumns(table));
  }

  static List<String> targetColumns(MigrationTable table) {
    List<String> columns = new ArrayList<>(table.columns());
    if (table == MigrationTable.POLICY_HOLDERS) {
      columns.addAll(PolicyHolderPii.TARGET_PII_COLUMNS);
    }
    return columns;
  }

  static String upsertSql(MigrationTable table) {
    List<String> columns = targetColumns(table);
    return insertSql(table, columns)
        + " ON CONFLICT ("
        + String.join(", ", table.keyColumns())
        + ") DO UPDATE SET "
        + columns.stream()
            .filter(c -> !table.keyColumns().contains(c))
            .map(c -> c + " = EXCLUDED." + c)
            .collect(Collectors.joining(", "));
  }

  private static String insertSql(MigrationTable table, List<String> columns) {
    return "INSERT INTO "
        + table.targetTable()
        + " ("
        + String.join(", ", columns)
        + ") VALUES ("
        + columns.stream().map(c -> ":" + c).collect(Collectors.joining(", "))
        + ")";
  }
}
