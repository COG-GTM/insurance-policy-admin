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
 * or before the rating date, so a whole rating table is loaded in two queries per rating date.
 */
public class RatingRateJdbcRepository {

  private final JdbcClient jdbc;

  public RatingRateJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<BaseRate> baseRatesInForce(LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT DISTINCT ON (policy_type) policy_type, effective_date, base_rate
            FROM pasapp.base_rates WHERE effective_date <= ?
            ORDER BY policy_type, effective_date DESC
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
            SELECT DISTINCT ON (parameter_code) parameter_code, effective_date, parameter_value
            FROM pasapp.rating_parameters WHERE effective_date <= ?
            ORDER BY parameter_code, effective_date DESC
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
