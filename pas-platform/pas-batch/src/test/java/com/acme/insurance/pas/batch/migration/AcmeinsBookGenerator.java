package com.acme.insurance.pas.batch.migration;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

public final class AcmeinsBookGenerator {
  private static final LocalDate EFFECTIVE_DATE = LocalDate.of(2025, 1, 1);

  private AcmeinsBookGenerator() {}

  public static void populate(JdbcTemplate jdbc, int policyCount) {
    if (policyCount < 0) {
      throw new IllegalArgumentException("Policy count cannot be negative");
    }
    jdbc.update(
        """
        INSERT INTO acmeins.territory_factors
            (territory_code, effective_date, rating_factor)
        VALUES ('T00001', ?, 1.0750)
        """,
        EFFECTIVE_DATE);
    for (int index = 1; index <= policyCount; index++) {
      String custId = "C" + String.format("%09d", index);
      String policyNumber = "POL" + String.format("%09d", index);
      String type = index % 7 == 0 ? "ZZZ" : index % 2 == 0 ? "HOM" : "AUT";
      insertHolder(jdbc, custId, index);
      insertPolicy(jdbc, policyNumber, custId, type, index);
      insertChildren(jdbc, policyNumber, index);
    }
  }

  private static void insertHolder(JdbcTemplate jdbc, String custId, int index) {
    jdbc.update(
        """
        INSERT INTO acmeins.policy_holders
            (cust_id, cust_type, last_name, first_name, date_of_birth, ssn_last4, tax_id,
             credit_score, created_date, last_updated)
        VALUES (?, 'I', ?, ?, DATE '1980-01-01', '1234', 'TAX0000001', 700, ?, TIMESTAMP '2025-01-01 12:00:00')
        """,
        custId,
        "Holder" + index,
        "Policy" + index,
        EFFECTIVE_DATE);
  }

  private static void insertPolicy(
      JdbcTemplate jdbc, String policyNumber, String custId, String type, int index) {
    jdbc.update(
        """
        INSERT INTO acmeins.policies
            (policy_number, policy_type, policy_status, effective_date, expiry_date,
             policyholder_id, agent_code, branch_code, total_premium, deductible,
             coverage_limit, inception_date, renewal_count, uw_status, risk_score,
             web_indicator, api_flag, last_updated, updated_by)
        VALUES (?, ?, 'AC', ?, ?, ?, 'AGT001', 'B001', ?, 500.00,
                ?, ?, 0, 'AP', 700, 'N', 'N',
                TIMESTAMP '2025-01-01 12:00:00', 'GENERATR')
        """,
        policyNumber,
        type,
        EFFECTIVE_DATE,
        EFFECTIVE_DATE.plusYears(1),
        custId,
        totalPremium(index),
        BigDecimal.valueOf((long) coverageCount(index) * 25_000L),
        EFFECTIVE_DATE.minusDays(index));
  }

  private static void insertChildren(JdbcTemplate jdbc, String policyNumber, int index) {
    int coverageCount = coverageCount(index);
    for (int sequence = 1; sequence <= coverageCount; sequence++) {
      String coverageType = index % 7 == 0 && sequence == 1 ? "MYST" : "AUTO";
      BigDecimal premium = BigDecimal.valueOf(200L + sequence * 25L, 0);
      jdbc.update(
          """
          INSERT INTO acmeins.coverages
              (policy_number, sequence_num, coverage_type, description, coverage_limit,
               deductible, premium, effective_date, expiry_date, status, coinsurance_pct,
               rating_territory, class_code)
          VALUES (?, ?, ?, 'Generated coverage', 25000.00, 500.00, ?, ?, ?, 'AC', 100,
                  'T00001', 'CLASS')
          """,
          policyNumber,
          sequence,
          coverageType,
          premium,
          EFFECTIVE_DATE,
          EFFECTIVE_DATE.plusYears(1));
      jdbc.update(
          """
          INSERT INTO acmeins.premiums
              (policy_number, coverage_seq, term_effective_date, term_expiry_date,
               base_rate, territory_factor, class_factor, total_premium, calc_date, calc_by)
          VALUES (?, ?, ?, ?, 100.0000, 1.0750, 1.0000, ?, ?, 'GENERATR')
          """,
          policyNumber,
          sequence,
          EFFECTIVE_DATE,
          EFFECTIVE_DATE.plusYears(1),
          premium,
          EFFECTIVE_DATE);
    }
    jdbc.update(
        """
        INSERT INTO acmeins.endorsements
            (policy_number, endorsement_seq, endorsement_type, effective_date, description,
             premium_adjustment, processed_date, processed_by)
        VALUES (?, 1, 'LCH', ?, 'Generated endorsement', 10.00,
                TIMESTAMP '2025-01-01 12:00:00', 'GENERATR')
        """,
        policyNumber,
        EFFECTIVE_DATE);
    jdbc.update(
        """
        INSERT INTO acmeins.underwriting_decisions
            (policy_number, decision_date, decision_code, risk_score, decision_reason,
             underwriter_id, created_timestamp)
        VALUES (?, ?, ?, 700, 'Generated decision', 'UW000001',
                TIMESTAMP '2025-01-01 12:00:00')
        """,
        policyNumber,
        EFFECTIVE_DATE,
        index % 7 == 0 ? "ZZ" : "AP");
  }

  private static int coverageCount(int index) {
    return 1 + (index % 4);
  }

  private static BigDecimal totalPremium(int index) {
    int coverageCount = coverageCount(index);
    long total = 200L * coverageCount + 25L * coverageCount * (coverageCount + 1) / 2;
    return BigDecimal.valueOf(total, 0);
  }
}
