package com.acme.insurance.pas.batch.migration.parallelrun;

import com.acme.insurance.pas.batch.migration.MigrationTable;
import com.acme.insurance.pas.batch.migration.SourceDatabase;
import com.acme.insurance.pas.batch.migration.SourceRows;
import com.acme.insurance.pas.batch.migration.reconcile.Canonical;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Legacy side of the PREMIUMS comparison: the mainframe PREMBAT output, {@code ACMEINS.PREMIUMS
 * WHERE CALC_DATE = d}, read over the read-only source JDBC pool. The new side is {@link
 * PremiumJobOutputSource}.
 */
public final class JdbcPremiumOutputSource implements PremiumOutputSource {

  static final List<String> COLUMNS = MigrationTable.PREMIUMS.columns();

  private final JdbcTemplate jdbc;
  private final String sql;
  private final String location;

  private JdbcPremiumOutputSource(JdbcTemplate jdbc, String sql, String location) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.location = location;
  }

  public static JdbcPremiumOutputSource legacy(SourceDatabase source) {
    String table = source.table(MigrationTable.PREMIUMS.sourceTable());
    return new JdbcPremiumOutputSource(
        source.jdbc(),
        "SELECT "
            + String.join(", ", COLUMNS).toUpperCase(Locale.ROOT)
            + " FROM "
            + table
            + " WHERE CALC_DATE = ?",
        table + " (CALC_DATE = business date)");
  }

  @Override
  public String location() {
    return location;
  }

  @Override
  public Optional<Map<String, String>> premiums(LocalDate businessDate) {
    return Optional.of(read(jdbc, sql, Date.valueOf(businessDate)));
  }

  /** Canonical PREMIUMS records keyed by primary key. */
  static Map<String, String> read(JdbcTemplate jdbc, String sql, Object... args) {
    Map<String, String> rows = new TreeMap<>();
    jdbc.query(
        sql,
        rs -> {
          Map<String, Object> row = SourceRows.INSTANCE.mapRow(rs, 0);
          rows.put(Canonical.key(MigrationTable.PREMIUMS, row), Canonical.record(COLUMNS, row));
        },
        args);
    return rows;
  }
}
