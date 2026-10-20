package com.acme.insurance.pas.batch.extract;

import static com.acme.insurance.pas.batch.extract.EbcdicCollation.key;
import static com.acme.insurance.pas.batch.extract.EbcdicCollation.sortKeys;

/**
 * PostgreSQL ports of the DSNTEP2 SYSIN in {@code jcl/DAILY-EXTRACT.jcl} (STEP010 / STEP020).
 * Filters, columns and ORDER BY are unchanged; {@code CURRENT DATE} becomes the {@code
 * :businessDate} job parameter so reruns and backfills select what the original run selected
 * (Q-32).
 */
public final class ExtractQueries {

  public static final String POLICY_EXTRACT =
      sortKeys(
          """
      SELECT policy_number, policy_type, policy_status,
             effective_date, expiry_date,
             policyholder_id, agent_code, branch_code,
             total_premium, deductible, coverage_limit,
             uw_status, risk_score
      FROM pas.policies
      WHERE policy_status IN ('AC', 'PN')
        AND last_updated >= CAST(:businessDate AS date) - 1
      ORDER BY %s NULLS LAST
      """,
          key("policy_number"));

  public static final String COVERAGE_EXTRACT =
      sortKeys(
          """
      SELECT policy_number, sequence_num, coverage_type,
             description, coverage_limit, deductible,
             premium, effective_date, expiry_date, status
      FROM pas.coverages
      WHERE status = 'AC'
      ORDER BY %s NULLS LAST, sequence_num NULLS LAST
      """,
          key("policy_number"));

  private ExtractQueries() {}
}
