package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
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
}
