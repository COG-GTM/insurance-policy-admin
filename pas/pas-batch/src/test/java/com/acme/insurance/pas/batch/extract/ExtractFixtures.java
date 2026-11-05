package com.acme.insurance.pas.batch.extract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Synthetic policies and coverages (no real customer data) for the extract tests. */
final class ExtractFixtures {

  private final JdbcClient jdbc;

  ExtractFixtures(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void reset() {
    jdbc.sql("DELETE FROM acmeins.coverages").update();
    jdbc.sql("DELETE FROM acmeins.policies").update();
    jdbc.sql("DELETE FROM pasapp.extract_delivery").update();
  }

  void policy(
      String number,
      String type,
      String status,
      String branch,
      String agent,
      BigDecimal premium,
      Integer riskScore,
      LocalDateTime lastUpdated) {
    jdbc.sql(
            """
            INSERT INTO acmeins.policies (policy_number, policy_type, policy_status, effective_date,
              expiry_date, policyholder_id, agent_code, branch_code, total_premium, deductible,
              coverage_limit, uw_status, risk_score, last_updated)
            VALUES (:n, :type, :status, DATE '2026-01-01', DATE '2027-01-01', 'H000000001', :agent,
              :branch, :premium, 500.00, 250000.00, 'AP', :risk, :updated)
            """)
        .param("n", number)
        .param("type", type)
        .param("status", status)
        .param("agent", agent)
        .param("branch", branch)
        .param("premium", premium)
        .param("risk", riskScore)
        .param("updated", lastUpdated)
        .update();
  }

  void coverage(
      String policy,
      int seq,
      String type,
      String description,
      BigDecimal limit,
      BigDecimal premium,
      String status,
      String territory,
      String classCode) {
    jdbc.sql(
            """
            INSERT INTO acmeins.coverages (policy_number, sequence_num, coverage_type, description,
              coverage_limit, deductible, premium, effective_date, expiry_date, status,
              rating_territory, class_code)
            VALUES (:p, :seq, :type, :description, :limit, 250.00, :premium, DATE '2026-01-01',
              DATE '2027-01-01', :status, :territory, :class)
            """)
        .param("p", policy)
        .param("seq", seq)
        .param("type", type)
        .param("description", description)
        .param("limit", limit)
        .param("premium", premium)
        .param("status", status)
        .param("territory", territory)
        .param("class", classCode)
        .update();
  }

  void touch(String policy, LocalDateTime lastUpdated) {
    jdbc.sql("UPDATE acmeins.policies SET last_updated = :t WHERE policy_number = :p")
        .param("t", lastUpdated)
        .param("p", policy)
        .update();
  }

  void policyStatus(String policy, String status, LocalDateTime lastUpdated) {
    jdbc.sql(
            "UPDATE acmeins.policies SET policy_status = :s, last_updated = :t"
                + " WHERE policy_number = :p")
        .param("s", status)
        .param("t", lastUpdated)
        .param("p", policy)
        .update();
  }

  void coverageStatus(String policy, int seq, String status) {
    jdbc.sql(
            "UPDATE acmeins.coverages SET status = :s WHERE policy_number = :p AND sequence_num = :q")
        .param("s", status)
        .param("p", policy)
        .param("q", seq)
        .update();
  }

  /** A book that exercises NULLs, negative amounts, ties in the exposure order and status mix. */
  void seedBook(LocalDate businessDate) {
    LocalDateTime today = businessDate.atTime(9, 30);
    LocalDateTime yesterday = businessDate.minusDays(1).atTime(0, 0);
    LocalDateTime old = businessDate.minusDays(2).atTime(23, 59, 59);
    policy("POL000000001", "AUT", "AC", "NE01", "AG0001", new BigDecimal("1234.56"), 42, today);
    policy("POL000000002", "AUT", "AC", "NE01", null, null, null, yesterday);
    policy("POL000000003", "HOM", "PN", "NE02", "AG0002", new BigDecimal("-15.00"), 7, old);
    policy("POL000000004", "HOM", "CN", "NE02", "AG0002", new BigDecimal("99.99"), 1, today);
    policy("POL000000005", "AUT", "AC", "NE01", "AG0003", new BigDecimal("0.00"), 0, old);
    coverage(
        "POL000000001",
        1,
        "BI",
        "Bodily injury, per person",
        big("100000.00"),
        big("410.10"),
        "AC",
        "CT0001",
        "A1001");
    coverage(
        "POL000000001",
        2,
        "PD",
        "Property damage \"standard\"",
        big("50000.00"),
        big("120.00"),
        "AC",
        "CT0001",
        null);
    coverage(
        "POL000000002", 1, "BI", null, big("100000.00"), big("390.00"), "AC", "CT0001", "A1001");
    coverage("POL000000002", 2, "COLL", "Collision", null, big("200.50"), "AC", null, "A1002");
    coverage(
        "POL000000003",
        1,
        "DWEL",
        "Dwelling",
        big("350000.00"),
        big("880.00"),
        "AC",
        "CT0002",
        "H2001");
    coverage(
        "POL000000004",
        1,
        "DWEL",
        "Dwelling",
        big("200000.00"),
        big("500.00"),
        "AC",
        "CT0002",
        "H2001");
    coverage(
        "POL000000005",
        1,
        "BI",
        "Bodily injury",
        big("25000.00"),
        big("-10.00"),
        "CN",
        "CT0003",
        "A1001");
    coverage(
        "POL000000005",
        2,
        "PD",
        "Property damage",
        big("25000.00"),
        big("75.25"),
        "AC",
        "CT0003",
        "A1003");
  }

  static BigDecimal big(String value) {
    return new BigDecimal(value);
  }
}
