package com.acme.insurance.pas.policy.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RatingFactorRepository {
  private final JdbcClient jdbc;

  public RatingFactorRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<BigDecimal> resolveTerritoryFactor(
      String territoryCode, LocalDate effectiveDate) {
    return jdbc.sql(
            """
            SELECT factor.rating_factor
            FROM pas.territory_factors factor
            JOIN pas.rating_factor_sets factor_set ON factor_set.set_code = factor.factor_set
            WHERE factor_set.active
              AND factor.territory_code IN (:territoryCode, '*')
              AND factor.effective_date <= :effectiveDate
            ORDER BY CASE WHEN factor.territory_code = :territoryCode THEN 0 ELSE 1 END,
                     factor.effective_date DESC
            LIMIT 1
            """)
        .param("territoryCode", territoryCode)
        .param("effectiveDate", effectiveDate)
        .query(BigDecimal.class)
        .optional();
  }

  public Optional<BigDecimal> resolveClassFactor(
      String policyType, String classCode, LocalDate effectiveDate) {
    return jdbc.sql(
            """
            SELECT factor.rating_factor
            FROM pas.class_factors factor
            JOIN pas.rating_factor_sets factor_set ON factor_set.set_code = factor.factor_set
            WHERE factor_set.active
              AND factor.policy_type IN (:policyType, '*')
              AND factor.class_code IN (:classCode, '*')
              AND factor.effective_date <= :effectiveDate
            ORDER BY
                (CASE WHEN factor.policy_type = :policyType THEN 1 ELSE 0 END
                 + CASE WHEN factor.class_code = :classCode THEN 1 ELSE 0 END) DESC,
                CASE WHEN factor.policy_type = :policyType THEN 0 ELSE 1 END,
                CASE WHEN factor.class_code = :classCode THEN 0 ELSE 1 END,
                factor.effective_date DESC
            LIMIT 1
            """)
        .param("policyType", policyType)
        .param("classCode", classCode)
        .param("effectiveDate", effectiveDate)
        .query(BigDecimal.class)
        .optional();
  }
}
