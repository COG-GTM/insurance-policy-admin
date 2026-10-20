package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@link RatingTables} over the {@code pas} schema (Flyway V3 + V5 in pas-policy-service; the batch
 * reads the same `pas` schema). Uses PostgreSQL {@code DISTINCT ON} to take the latest row per code
 * with {@code effective_date <= asOf}. Read-only; one query per table per snapshot, all in one
 * REPEATABLE READ transaction.
 */
public class JdbcRatingTables implements RatingTables {

  private static final String FACTOR_SET_EXISTS =
      "SELECT count(*) FROM pas.rating_factor_sets WHERE factor_set = ?";
  private static final String BASE_RATES =
      "SELECT DISTINCT ON (policy_type) policy_type, base_rate, effective_date FROM pas.base_rates"
          + " WHERE effective_date <= ? ORDER BY policy_type, effective_date DESC";
  private static final String TERRITORY_FACTORS =
      "SELECT DISTINCT ON (territory_code) territory_code, rating_factor, effective_date"
          + " FROM pas.territory_factors WHERE factor_set = ? AND effective_date <= ?"
          + " ORDER BY territory_code, effective_date DESC";
  private static final String CLASS_FACTORS =
      "SELECT DISTINCT ON (class_code) class_code, rating_factor, effective_date"
          + " FROM pas.class_factors WHERE factor_set = ? AND effective_date <= ?"
          + " ORDER BY class_code, effective_date DESC";
  private static final String PARAMETERS =
      "SELECT effective_date, rate_version, tax_rate, surcharge_amt, experience_mod, schedule_mod, discount_pct"
          + " FROM pas.rating_parameters WHERE effective_date <= ?"
          + " ORDER BY effective_date DESC LIMIT 1";

  private final JdbcTemplate jdbc;

  public JdbcRatingTables(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /**
   * Reads every table on one connection. Outside a caller's transaction the reads share one
   * REPEATABLE READ transaction, so a filing committed mid-load is seen whole or not at all; inside
   * a caller's transaction they use the caller's read view.
   */
  @Override
  public RatingSnapshot load(LocalDate asOf, String factorSet) {
    return jdbc.execute(
        (ConnectionCallback<RatingSnapshot>)
            con -> {
              JdbcTemplate on = new JdbcTemplate(new SingleConnectionDataSource(con, true));
              if (!con.getAutoCommit()) {
                return read(on, asOf, factorSet);
              }
              int isolation = con.getTransactionIsolation();
              con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
              con.setAutoCommit(false);
              try {
                RatingSnapshot snapshot = read(on, asOf, factorSet);
                con.commit();
                return snapshot;
              } catch (RuntimeException | SQLException e) {
                con.rollback();
                throw e;
              } finally {
                con.setAutoCommit(true);
                con.setTransactionIsolation(isolation);
              }
            });
  }

  private static RatingSnapshot read(JdbcTemplate jdbc, LocalDate asOf, String factorSet) {
    Date date = Date.valueOf(asOf);
    Integer sets = jdbc.queryForObject(FACTOR_SET_EXISTS, Integer.class, factorSet);
    if (sets == null || sets == 0) {
      throw new RatingDataException("unknown rating factor set " + factorSet);
    }
    List<LocalDate> paramsEffective = new ArrayList<>();
    List<RatingParameters> params =
        jdbc.query(
            PARAMETERS,
            (rs, i) -> {
              paramsEffective.add(rs.getDate("effective_date").toLocalDate());
              return new RatingParameters(
                  rs.getString("rate_version"),
                  rs.getBigDecimal("tax_rate"),
                  rs.getBigDecimal("surcharge_amt"),
                  rs.getBigDecimal("experience_mod"),
                  rs.getBigDecimal("schedule_mod"),
                  rs.getBigDecimal("discount_pct"));
            },
            date);
    if (params.isEmpty()) {
      throw new RatingDataException("no rating parameters effective on " + asOf);
    }
    LocalDate[] newest = {null};
    Map<String, BigDecimal> baseRates = codes(jdbc, newest, BASE_RATES, date);
    Map<String, BigDecimal> territory = codes(jdbc, newest, TERRITORY_FACTORS, factorSet, date);
    Map<String, BigDecimal> classes = codes(jdbc, newest, CLASS_FACTORS, factorSet, date);
    RatingParameters p = params.get(0);
    RatingSnapshot.requireVersioned(newest[0], paramsEffective.get(0), p.rateVersion(), asOf);
    return new RatingSnapshot(asOf, factorSet, baseRates, territory, classes, p);
  }

  private static Map<String, BigDecimal> codes(
      JdbcTemplate jdbc, LocalDate[] newest, String sql, Object... args) {
    Map<String, BigDecimal> out = new HashMap<>();
    RowCallbackHandler row =
        rs -> {
          out.put(rs.getString(1), rs.getBigDecimal(2));
          LocalDate effective = rs.getDate(3).toLocalDate();
          if (newest[0] == null || effective.isAfter(newest[0])) {
            newest[0] = effective;
          }
        };
    jdbc.query(sql, row, args);
    return out;
  }
}
