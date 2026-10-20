package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * {@link RatingTables} over the {@code pas} schema (Flyway V3 + V5 in pas-policy-service; the batch
 * reads the same `pas` schema). Uses PostgreSQL {@code DISTINCT ON} to take the latest row per code
 * with {@code effective_date <= asOf}. Read-only; one query per table per snapshot.
 */
public class JdbcRatingTables implements RatingTables {

  private static final String FACTOR_SET_EXISTS =
      "SELECT count(*) FROM pas.rating_factor_sets WHERE factor_set = ?";
  private static final String BASE_RATES =
      "SELECT DISTINCT ON (policy_type) policy_type, base_rate FROM pas.base_rates"
          + " WHERE effective_date <= ? ORDER BY policy_type, effective_date DESC";
  private static final String TERRITORY_FACTORS =
      "SELECT DISTINCT ON (territory_code) territory_code, rating_factor"
          + " FROM pas.territory_factors WHERE factor_set = ? AND effective_date <= ?"
          + " ORDER BY territory_code, effective_date DESC";
  private static final String CLASS_FACTORS =
      "SELECT DISTINCT ON (class_code) class_code, rating_factor"
          + " FROM pas.class_factors WHERE factor_set = ? AND effective_date <= ?"
          + " ORDER BY class_code, effective_date DESC";
  private static final String PARAMETERS =
      "SELECT rate_version, tax_rate, surcharge_amt, experience_mod, schedule_mod, discount_pct"
          + " FROM pas.rating_parameters WHERE effective_date <= ?"
          + " ORDER BY effective_date DESC LIMIT 1";

  private final JdbcTemplate jdbc;

  public JdbcRatingTables(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public RatingSnapshot load(LocalDate asOf, String factorSet) {
    Date date = Date.valueOf(asOf);
    Integer sets = jdbc.queryForObject(FACTOR_SET_EXISTS, Integer.class, factorSet);
    if (sets == null || sets == 0) {
      throw new RatingDataException("unknown rating factor set " + factorSet);
    }
    List<RatingParameters> params =
        jdbc.query(
            PARAMETERS,
            (rs, i) ->
                new RatingParameters(
                    rs.getString("rate_version"),
                    rs.getBigDecimal("tax_rate"),
                    rs.getBigDecimal("surcharge_amt"),
                    rs.getBigDecimal("experience_mod"),
                    rs.getBigDecimal("schedule_mod"),
                    rs.getBigDecimal("discount_pct")),
            date);
    if (params.isEmpty()) {
      throw new RatingDataException("no rating parameters effective on " + asOf);
    }
    return new RatingSnapshot(
        asOf,
        factorSet,
        codes(BASE_RATES, date),
        codes(TERRITORY_FACTORS, factorSet, date),
        codes(CLASS_FACTORS, factorSet, date),
        params.get(0));
  }

  private Map<String, BigDecimal> codes(String sql, Object... args) {
    Map<String, BigDecimal> out = new HashMap<>();
    RowCallbackHandler row = rs -> out.put(rs.getString(1), rs.getBigDecimal(2));
    jdbc.query(sql, row, args);
    return out;
  }
}
