package com.acme.insurance.pas.rating.jdbc;

import com.acme.insurance.pas.rating.RatingParameters;
import com.acme.insurance.pas.rating.RatingTableRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcRatingTableRepository implements RatingTableRepository {
  private final JdbcClient jdbc;

  public JdbcRatingTableRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RatingParameters> findParameters(String policyType, LocalDate ratingDate) {
    return jdbc.sql(
            """
            SELECT rtrim(policy_type) AS policy_type_key, effective_date, base_premium,
                   experience_mod, schedule_mod, tax_rate, surcharge_amount
            FROM pas.rating_parameters
            WHERE (policy_type = :policyType OR policy_type = '*')
              AND effective_date <= :ratingDate
            ORDER BY CASE WHEN policy_type = :policyType THEN 0 ELSE 1 END,
                     effective_date DESC
            LIMIT 1
            """)
        .param("policyType", policyType)
        .param("ratingDate", ratingDate)
        .query(
            (rs, rowNum) ->
                new RatingParameters(
                    rs.getString("policy_type_key"),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getBigDecimal("base_premium"),
                    rs.getBigDecimal("experience_mod"),
                    rs.getBigDecimal("schedule_mod"),
                    rs.getBigDecimal("tax_rate"),
                    rs.getBigDecimal("surcharge_amount")))
        .optional();
  }

  @Override
  public Optional<String> findActiveFactorSet() {
    return jdbc.sql("SELECT set_code FROM pas.rating_factor_sets WHERE active")
        .query(String.class)
        .optional();
  }

  @Override
  public Optional<BigDecimal> findTerritoryFactor(
      String factorSet, String territoryCode, LocalDate ratingDate) {
    String code = territoryCode == null ? "*" : territoryCode;
    return jdbc.sql(
            """
            SELECT factor.rating_factor
            FROM pas.territory_factors factor
            WHERE factor.factor_set = :factorSet
              AND factor.territory_code IN (:territoryCode, '*')
              AND factor.effective_date <= :ratingDate
            ORDER BY CASE WHEN factor.territory_code = :territoryCode THEN 0 ELSE 1 END,
                     factor.effective_date DESC
            LIMIT 1
            """)
        .param("factorSet", factorSet)
        .param("territoryCode", code)
        .param("ratingDate", ratingDate)
        .query(BigDecimal.class)
        .optional();
  }

  @Override
  public Optional<BigDecimal> findClassFactor(
      String factorSet, String policyType, String classCode, LocalDate ratingDate) {
    String normalizedClassCode = classCode == null ? "*" : classCode;
    return jdbc.sql(
            """
            SELECT factor.rating_factor
            FROM pas.class_factors factor
            WHERE factor.factor_set = :factorSet
              AND factor.policy_type IN (:policyType, '*')
              AND factor.class_code IN (:classCode, '*')
              AND factor.effective_date <= :ratingDate
            ORDER BY
                (CASE WHEN factor.policy_type = :policyType THEN 1 ELSE 0 END
                 + CASE WHEN factor.class_code = :classCode THEN 1 ELSE 0 END) DESC,
                CASE WHEN factor.policy_type = :policyType THEN 0 ELSE 1 END,
                CASE WHEN factor.class_code = :classCode THEN 0 ELSE 1 END,
                factor.effective_date DESC
            LIMIT 1
            """)
        .param("factorSet", factorSet)
        .param("policyType", policyType)
        .param("classCode", normalizedClassCode)
        .param("ratingDate", ratingDate)
        .query(BigDecimal.class)
        .optional();
  }
}
