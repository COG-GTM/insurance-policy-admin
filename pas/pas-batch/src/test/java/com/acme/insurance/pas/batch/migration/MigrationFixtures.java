package com.acme.insurance.pas.batch.migration;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic ACMEINS rows for the migration tests; no real customer data. */
final class MigrationFixtures {

  private static final String[] TYPES = {"AUT", "HOM", "COM"};
  private static final String[] BRANCHES = {"001", "002"};

  private MigrationFixtures() {}

  static String policy(int i) {
    return "POL%08d".formatted(i);
  }

  static String type(int i) {
    return TYPES[i % TYPES.length];
  }

  static String branch(int i) {
    return BRANCHES[i % BRANCHES.length];
  }

  static void seed(JdbcTemplate jdbc, int policies) {
    jdbc.update(
        "INSERT INTO acmeins.territory_factors (territory_code, effective_date, rating_factor)"
            + " VALUES ('NY0001', DATE '2020-01-01', 1.1500), ('NJ0002', DATE '2020-01-01', 0.9800)");
    for (int i = 1; i <= policies; i++) {
      seedPolicy(jdbc, i);
    }
  }

  static void seedPolicy(JdbcTemplate jdbc, int i) {
    String policy = policy(i);
    String holder = "C%09d".formatted(i);
    Date eff = Date.valueOf("2026-01-01");
    Date exp = Date.valueOf("2027-01-01");
    jdbc.update(
        "INSERT INTO acmeins.policy_holders (cust_id, cust_type, last_name, first_name, city,"
            + " state_code, zip_code, created_date) VALUES (?, 'I', ?, ?, 'ALBANY', 'NY', '12207', ?)",
        holder,
        "TESTLAST" + i,
        "TESTFIRST",
        eff);
    jdbc.update(
        "INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,"
            + " expiry_date, policyholder_id, agent_code, branch_code, total_premium, deductible,"
            + " coverage_limit, inception_date, last_updated, updated_by)"
            + " VALUES (?, ?, 'AC', ?, ?, ?, 'AG001', ?, ?, ?, ?, ?, ?, 'PASBATCH')",
        policy,
        type(i),
        eff,
        exp,
        holder,
        branch(i),
        new BigDecimal("1000.00").add(BigDecimal.valueOf(i)),
        new BigDecimal("500.00"),
        new BigDecimal("250000.00"),
        eff,
        Timestamp.valueOf("2026-01-01 08:00:00.123456"));
    for (int seq = 1; seq <= 2; seq++) {
      jdbc.update(
          "INSERT INTO acmeins.coverages (policy_number, sequence_num, coverage_type, description,"
              + " coverage_limit, deductible, premium, effective_date, expiry_date, status,"
              + " rating_territory, class_code) VALUES (?, ?, ?, 'COVERAGE', ?, 250.00, ?, ?, ?,"
              + " 'A', 'NY0001', 'C1')",
          policy,
          seq,
          seq == 1 ? "BI" : "PD",
          new BigDecimal("100000.00"),
          new BigDecimal("400.50").add(BigDecimal.valueOf(seq)),
          eff,
          exp);
      jdbc.update(
          "INSERT INTO acmeins.premiums (policy_number, coverage_seq, term_effective_date,"
              + " term_expiry_date, base_rate, total_premium, tax_amt, calc_date, calc_by)"
              + " VALUES (?, ?, ?, ?, 400.50, ?, 12.34, ?, 'PREMBAT')",
          policy,
          seq,
          eff,
          exp,
          new BigDecimal("412.84"),
          eff);
    }
    jdbc.update(
        "INSERT INTO acmeins.endorsements (policy_number, endorsement_seq, endorsement_type,"
            + " effective_date, description, premium_adjustment, processed_by)"
            + " VALUES (?, 1, 'CHG', ?, 'ADDRESS CHANGE', 0.00, 'PASUSER')",
        policy,
        eff);
    jdbc.update(
        "INSERT INTO acmeins.underwriting_decisions (policy_number, decision_date, decision_code,"
            + " risk_score, decision_reason, underwriter_id) VALUES (?, ?, 'AP', 42, 'AUTO', 'UW001')",
        policy,
        eff);
  }
}
