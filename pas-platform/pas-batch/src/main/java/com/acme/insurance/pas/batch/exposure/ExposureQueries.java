package com.acme.insurance.pas.batch.exposure;

import static com.acme.insurance.pas.batch.extract.EbcdicCollation.key;
import static com.acme.insurance.pas.batch.extract.EbcdicCollation.sortKeys;

/**
 * PostgreSQL port of the DSNTEP2 SYSIN in {@code jcl/MONTHLY-EXPOSURE.jcl} STEP010. Join, filters,
 * GROUP BY and aggregates are unchanged (Q-33: {@code POLICY_COUNT} counts coverage rows and the
 * premium total sums {@code COVERAGES.PREMIUM}). The JCL orders only by POLICY_TYPE, BRANCH_CODE;
 * the remaining GROUP BY columns are appended so the order inside those groups is deterministic.
 */
public final class ExposureQueries {

  public static final String MONTHLY_EXPOSURE =
      sortKeys(
          """
      SELECT p.policy_type,
             p.branch_code,
             c.coverage_type,
             c.rating_territory,
             c.class_code,
             COUNT(*) AS policy_count,
             SUM(c.coverage_limit) AS total_exposure,
             SUM(c.premium) AS total_premium,
             SUM(c.deductible) AS total_deductible
      FROM pas.policies p
      JOIN pas.coverages c
        ON p.policy_number = c.policy_number
      WHERE p.policy_status = 'AC'
        AND c.status = 'AC'
      GROUP BY p.policy_type, p.branch_code,
               c.coverage_type, c.rating_territory,
               c.class_code
      ORDER BY %s NULLS LAST, %s NULLS LAST,
               %s NULLS LAST, %s NULLS LAST, %s NULLS LAST
      """,
          key("p.policy_type"),
          key("p.branch_code"),
          key("c.coverage_type"),
          key("c.rating_territory"),
          key("c.class_code"));

  private ExposureQueries() {}
}
