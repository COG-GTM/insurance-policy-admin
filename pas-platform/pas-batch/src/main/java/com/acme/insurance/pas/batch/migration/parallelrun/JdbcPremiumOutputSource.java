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
 * Reads PREMIUMS rows for a business date over JDBC.
 *
 * <ul>
 *   <li>{@link #legacy}: the mainframe PREMBAT output, {@code ACMEINS.PREMIUMS WHERE CALC_DATE =
 *       d}.
 *   <li>{@link #shadow}: the new premium job's parallel-run output in {@code pas_shadow.premiums}
 *       (latest {@code shadow_run_id} for the business date). Empty until AB-523 writes there.
 * </ul>
 */
public final class JdbcPremiumOutputSource implements PremiumOutputSource {

  private static final List<String> COLUMNS = MigrationTable.PREMIUMS.columns();

  private final JdbcTemplate jdbc;
  private final String sql;
  private final String location;
  private final boolean emptyMeansMissing;

  private JdbcPremiumOutputSource(
      JdbcTemplate jdbc, String sql, String location, boolean emptyMeansMissing) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.location = location;
    this.emptyMeansMissing = emptyMeansMissing;
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
        table + " (CALC_DATE = business date)",
        false);
  }

  /*
   * TODO(AB-523): premiumRecalculationJob writes pas.premiums directly. For the parallel run it
   * needs a shadow mode that writes the same rows to pas_shadow.premiums with shadow_run_id
   * (sortable, e.g. premiumRecalculationJob-<zero-padded execution id>) and business_date; until
   * then this source is empty and the PREMIUMS output is reported NOT_AVAILABLE (INCOMPLETE).
   */
  public static JdbcPremiumOutputSource shadow(JdbcTemplate target) {
    return new JdbcPremiumOutputSource(
        target,
        "SELECT "
            + String.join(", ", COLUMNS)
            + " FROM pas_shadow.premiums WHERE business_date = ? AND shadow_run_id ="
            + " (SELECT MAX(shadow_run_id) FROM pas_shadow.premiums WHERE business_date = ?)",
        "pas_shadow.premiums (latest shadow_run_id for business date)",
        true);
  }

  @Override
  public String location() {
    return location;
  }

  @Override
  public Optional<Map<String, String>> premiums(LocalDate businessDate) {
    Date date = Date.valueOf(businessDate);
    Object[] args = emptyMeansMissing ? new Object[] {date, date} : new Object[] {date};
    Map<String, String> rows = new TreeMap<>();
    jdbc.query(
        sql,
        rs -> {
          Map<String, Object> row = SourceRows.INSTANCE.mapRow(rs, 0);
          rows.put(Canonical.key(MigrationTable.PREMIUMS, row), Canonical.record(COLUMNS, row));
        },
        args);
    return emptyMeansMissing && rows.isEmpty() ? Optional.empty() : Optional.of(rows);
  }
}
