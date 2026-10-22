package com.acme.insurance.pas.batch.extract;

final class ExtractSql {
  static final String POLICY =
      """
      SELECT policy_number, policy_type, policy_status, effective_date, expiry_date,
             policyholder_id, agent_code, branch_code, total_premium, deductible,
             coverage_limit, uw_status, risk_score
      FROM pas.policies
      WHERE policy_status IN ('AC','PN') AND last_updated >= ?
      ORDER BY policy_number COLLATE "C"
      """;

  static final String COVERAGE =
      """
      SELECT policy_number, sequence_num, coverage_type, description, coverage_limit,
             deductible, premium, effective_date, expiry_date, status
      FROM pas.coverages
      WHERE status = 'AC'
      ORDER BY policy_number COLLATE "C", sequence_num
      """;

  static final String EXPOSURE =
      """
      SELECT p.policy_type, p.branch_code, c.coverage_type, c.rating_territory,
             -- Q-42: COUNT(*) counts active coverage rows, not distinct policies.
             c.class_code, COUNT(*) AS policy_count, SUM(c.coverage_limit) AS total_exposure,
             SUM(c.premium) AS total_premium, SUM(c.deductible) AS total_deductible
      FROM pas.policies p
      JOIN pas.coverages c ON p.policy_number = c.policy_number
      WHERE p.policy_status = 'AC' AND c.status = 'AC'
      GROUP BY p.policy_type, p.branch_code, c.coverage_type, c.rating_territory, c.class_code
      ORDER BY p.policy_type COLLATE "C", p.branch_code COLLATE "C",
               c.coverage_type COLLATE "C", c.rating_territory COLLATE "C",
               c.class_code COLLATE "C"
      """;

  private ExtractSql() {}
}
