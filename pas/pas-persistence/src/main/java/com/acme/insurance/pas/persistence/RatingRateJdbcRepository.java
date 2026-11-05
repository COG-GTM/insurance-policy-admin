package com.acme.insurance.pas.persistence;

import com.acme.insurance.pas.domain.BaseRate;
import com.acme.insurance.pas.domain.RatingParameter;
import com.acme.insurance.pas.domain.RatingParameterCode;
import java.time.LocalDate;
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
