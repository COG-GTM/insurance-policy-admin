package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoverageRepository {
  private static final String SELECT =
      """
      SELECT policy_number, sequence_num, coverage_type, description, coverage_limit,
             deductible, premium, effective_date, expiry_date, status, coinsurance_pct,
             rating_territory, class_code
      FROM pas.coverages
      """;

  private final JdbcClient jdbc;

  public CoverageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Coverage coverage) {
    jdbc.sql(
            """
            INSERT INTO pas.coverages (
                policy_number, sequence_num, coverage_type, description, coverage_limit,
                deductible, premium, effective_date, expiry_date, status, coinsurance_pct,
                rating_territory, class_code
            ) VALUES (
                :policyNumber, :sequenceNum, :coverageType, :description, :coverageLimit,
                :deductible, :premium, :effectiveDate, :expiryDate, :status, :coinsurancePct,
                :ratingTerritory, :classCode
            )
            """)
        .param("policyNumber", coverage.policyNumber())
        .param("sequenceNum", coverage.sequenceNum())
        .param("coverageType", coverage.coverageType().raw())
        .param("description", coverage.description())
        .param("coverageLimit", coverage.coverageLimit())
        .param("deductible", coverage.deductible())
        .param("premium", coverage.premium())
        .param("effectiveDate", coverage.effectiveDate())
        .param("expiryDate", coverage.expiryDate())
        .param("status", coverage.status().raw())
        .param("coinsurancePct", coverage.coinsurancePct())
        .param("ratingTerritory", coverage.ratingTerritory())
        .param("classCode", coverage.classCode())
        .update();
  }

  public Optional<Coverage> findByKey(String policyNumber, int sequenceNum) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber AND sequence_num = :sequenceNum")
        .param("policyNumber", policyNumber)
        .param("sequenceNum", sequenceNum)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  public List<Coverage> findByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber ORDER BY sequence_num")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> map(row))
        .list();
  }

  private static Coverage map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Coverage(
        JdbcValues.charValue(row, "policy_number"),
        JdbcValues.integer(row, "sequence_num"),
        JdbcValues.coded(row, "coverage_type", CoverageType.class),
        row.getString("description"),
        row.getBigDecimal("coverage_limit"),
        row.getBigDecimal("deductible"),
        row.getBigDecimal("premium"),
        JdbcValues.date(row, "effective_date"),
        JdbcValues.date(row, "expiry_date"),
        JdbcValues.coded(row, "status", CoverageStatus.class),
        JdbcValues.integer(row, "coinsurance_pct"),
        JdbcValues.charValue(row, "rating_territory"),
        JdbcValues.charValue(row, "class_code"));
  }

  /** POLRNW 7000: moves every active coverage to the new term. */
  public int redateActive(String policyNumber, LocalDate effectiveDate, LocalDate expiryDate) {
    return jdbc.sql(
            "UPDATE pas.coverages SET effective_date = :effectiveDate, expiry_date = :expiryDate WHERE policy_number = :policyNumber AND status = 'AC'")
        .param("effectiveDate", effectiveDate)
        .param("expiryDate", expiryDate)
        .param("policyNumber", policyNumber)
        .update();
  }

  /** Start date of an active coverage, empty when the sequence is unknown or not active. */
  public java.util.Optional<LocalDate> activeCoverageStart(String policyNumber, int sequenceNum) {
    return jdbc.sql(
            "SELECT effective_date FROM pas.coverages WHERE policy_number = :policyNumber AND sequence_num = :sequenceNum AND status = 'AC'")
        .param("policyNumber", policyNumber)
        .param("sequenceNum", sequenceNum)
        .query(LocalDate.class)
        .optional();
  }

  /** Whether a coverage code is already carried by any policy of this type (legacy codes, Q-43). */
  public boolean coverageTypeInUse(String policyType, String coverageType) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM pas.coverages c JOIN pas.policies p ON p.policy_number = c.policy_number WHERE p.policy_type = :policyType AND c.coverage_type = :coverageType)")
        .param("policyType", policyType)
        .param("coverageType", coverageType)
        .query(Boolean.class)
        .single();
  }

  public int nextSequence(String policyNumber) {
    Integer max =
        jdbc.sql("SELECT MAX(sequence_num) FROM pas.coverages WHERE policy_number = :policyNumber")
            .param("policyNumber", policyNumber)
            .query(Integer.class)
            .optional()
            .orElse(null);
    return max == null ? 1 : max + 1;
  }

  /** Ends one active coverage on the given date and marks it cancelled. */
  public int cancel(String policyNumber, int sequenceNum, LocalDate expiryDate) {
    return jdbc.sql(
            "UPDATE pas.coverages SET status = 'CN', expiry_date = :expiryDate WHERE policy_number = :policyNumber AND sequence_num = :sequenceNum AND status = 'AC' AND effective_date <= :expiryDate")
        .param("expiryDate", expiryDate)
        .param("policyNumber", policyNumber)
        .param("sequenceNum", sequenceNum)
        .update();
  }

  public int cancelActive(String policyNumber, LocalDate expiryDate) {
    return jdbc.sql(
            "UPDATE pas.coverages SET status = 'CN', expiry_date = GREATEST(effective_date, :expiryDate) WHERE policy_number = :policyNumber AND status = 'AC'")
        .param("expiryDate", expiryDate)
        .param("policyNumber", policyNumber)
        .update();
  }

  public int changeLimit(String policyNumber, int sequenceNum, BigDecimal coverageLimit) {
    return jdbc.sql(
            "UPDATE pas.coverages SET coverage_limit = :coverageLimit WHERE policy_number = :policyNumber AND sequence_num = :sequenceNum AND status = 'AC'")
        .param("coverageLimit", coverageLimit)
        .param("policyNumber", policyNumber)
        .param("sequenceNum", sequenceNum)
        .update();
  }
}
