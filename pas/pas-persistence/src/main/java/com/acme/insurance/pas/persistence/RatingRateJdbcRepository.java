package com.acme.insurance.pas.persistence;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.ClassFactor;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingParameterCode;
import com.acme.insurance.pas.domain.RatingRateHistory;
import com.acme.insurance.pas.domain.TerritoryFactor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Effective-dated rate reference data for rating (AB-522): PASAPP.BASE_RATES and
 * PASAPP.RATING_PARAMETERS. Each read returns, per key, the row with the latest effective date on
 * or before the rating date, so a whole rating table is loaded in two queries per rating date. The
 * SQL is portable (correlated MAX, no DISTINCT ON) because the applications read DB2 until cutover.
 */
public class RatingRateJdbcRepository {

  private final JdbcClient jdbc;

  public RatingRateJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<BaseRate> baseRatesInForce(LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT r.policy_type, r.effective_date, r.base_rate
            FROM pasapp.base_rates r
            WHERE r.effective_date = (
              SELECT MAX(l.effective_date) FROM pasapp.base_rates l
              WHERE l.policy_type = r.policy_type AND l.effective_date <= ?)
            ORDER BY r.policy_type
            """)
        .param(ratingDate)
        .query(
            (rs, row) ->
                new BaseRate(
                    rs.getString("policy_type"),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getBigDecimal("base_rate")))
        .list();
  }

  /**
   * Every base-rate, parameter, territory-factor and class-factor row of every effective date, read
   * by one statement so all four tables come from the same committed version (a release that
   * commits a base rate, a parameter and a factor together is seen entirely or not at all). Used to
   * pin a batch run.
   */
  public RatingRateHistory history() {
    List<BaseRate> baseRates = new ArrayList<>();
    List<RatingParameter> parameters = new ArrayList<>();
    List<TerritoryFactor> territoryFactors = new ArrayList<>();
    List<ClassFactor> classFactors = new ArrayList<>();
    jdbc.sql(
            """
            SELECT 'B' AS row_kind, policy_type AS row_key, effective_date,
                   CAST(base_rate AS DECIMAL(11,4)) AS row_value
            FROM pasapp.base_rates
            UNION ALL
            SELECT 'P', parameter_code, effective_date, parameter_value
            FROM pasapp.rating_parameters
            UNION ALL
            SELECT 'T', territory_code, effective_date, CAST(rating_factor AS DECIMAL(11,4))
            FROM acmeins.territory_factors
            UNION ALL
            SELECT 'C', class_code, effective_date, CAST(rating_factor AS DECIMAL(11,4))
            FROM pasapp.class_factors
            """)
        .query(
            rs -> {
              String key = rs.getString("row_key");
              LocalDate effective = rs.getObject("effective_date", LocalDate.class);
              BigDecimal value = rs.getBigDecimal("row_value");
              // The UNION coerces CHAR(n) to a varying type, which may drop trailing blanks.
              switch (rs.getString("row_kind")) {
                case "B" ->
                    baseRates.add(new BaseRate(String.format("%-3s", key), effective, value));
                case "P" ->
                    parameters.add(
                        new RatingParameter(
                            RatingParameterCode.valueOf(key.trim()), effective, value));
                case "T" ->
                    territoryFactors.add(
                        new TerritoryFactor(String.format("%-6s", key), effective, value));
                default ->
                    classFactors.add(new ClassFactor(String.format("%-5s", key), effective, value));
              }
            });
    return new RatingRateHistory(baseRates, parameters, territoryFactors, classFactors);
  }

  public List<RatingParameter> parametersInForce(LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT r.parameter_code, r.effective_date, r.parameter_value
            FROM pasapp.rating_parameters r
            WHERE r.effective_date = (
              SELECT MAX(l.effective_date) FROM pasapp.rating_parameters l
              WHERE l.parameter_code = r.parameter_code AND l.effective_date <= ?)
            ORDER BY r.parameter_code
            """)
        .param(ratingDate)
        .query(
            (rs, row) ->
                new RatingParameter(
                    RatingParameterCode.valueOf(rs.getString("parameter_code")),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getBigDecimal("parameter_value")))
        .list();
  }

  public void insert(BaseRate rate) {
    jdbc.sql(
            "INSERT INTO pasapp.base_rates (policy_type, effective_date, base_rate)"
                + " VALUES (?, ?, ?)")
        .params(rate.policyType(), rate.effectiveDate(), rate.baseRate())
        .update();
  }

  public void insert(RatingParameter parameter) {
    jdbc.sql(
            "INSERT INTO pasapp.rating_parameters (parameter_code, effective_date, parameter_value)"
                + " VALUES (?, ?, ?)")
        .params(parameter.code().name(), parameter.effectiveDate(), parameter.value())
        .update();
  }
}
