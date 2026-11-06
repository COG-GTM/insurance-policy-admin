package com.acme.insurance.pas.persistence;

import com.acme.insurance.pas.domain.ClassFactor;
import com.acme.insurance.pas.domain.TerritoryFactor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Effective-dated rating reference data: ACMEINS.TERRITORY_FACTORS and PASAPP.CLASS_FACTORS. A
 * lookup returns the factor with the latest effective date on or before the rating date. Empty
 * means no row applies; rating then uses RatingFactors.NEUTRAL (quirk Q-03).
 */
public class RatingFactorJdbcRepository {

  private final JdbcClient jdbc;

  public RatingFactorJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<BigDecimal> territoryFactor(String territoryCode, LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT rating_factor FROM acmeins.territory_factors
            WHERE territory_code = ? AND effective_date <= ?
            ORDER BY effective_date DESC FETCH FIRST 1 ROW ONLY
            """)
        .params(territoryCode, ratingDate)
        .query(BigDecimal.class)
        .optional();
  }

  public Optional<BigDecimal> classFactor(String classCode, LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT rating_factor FROM pasapp.class_factors
            WHERE class_code = ? AND effective_date <= ?
            ORDER BY effective_date DESC FETCH FIRST 1 ROW ONLY
            """)
        .params(classCode, ratingDate)
        .query(BigDecimal.class)
        .optional();
  }

  public void insert(TerritoryFactor factor) {
    jdbc.sql(
            "INSERT INTO acmeins.territory_factors (territory_code, effective_date, rating_factor)"
                + " VALUES (?, ?, ?)")
        .params(factor.territoryCode(), factor.effectiveDate(), factor.ratingFactor())
        .update();
  }

  public void insert(ClassFactor factor) {
    jdbc.sql(
            "INSERT INTO pasapp.class_factors (class_code, effective_date, rating_factor)"
                + " VALUES (?, ?, ?)")
        .params(factor.classCode(), factor.effectiveDate(), factor.ratingFactor())
        .update();
  }
}
