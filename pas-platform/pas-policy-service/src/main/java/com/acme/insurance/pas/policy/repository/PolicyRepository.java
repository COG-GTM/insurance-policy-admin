package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import java.util.Optional;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyRepository {
  private static final String SELECT =
      """
      SELECT policy_number, policy_type, policy_status, effective_date, expiry_date,
             policyholder_id, agent_code, branch_code, total_premium, deductible, coverage_limit,
             inception_date, renewal_count, uw_status, risk_score, web_indicator, api_flag,
             last_updated, updated_by
      FROM pas.policies
      """;

  private final JdbcClient jdbc;

  public PolicyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Policy policy) {
    jdbc.sql(
            """
            INSERT INTO pas.policies (
                policy_number, policy_type, policy_status, effective_date, expiry_date,
                policyholder_id, agent_code, branch_code, total_premium, deductible,
                coverage_limit, inception_date, renewal_count, uw_status, risk_score,
                web_indicator, api_flag, last_updated, updated_by
            ) VALUES (
                :policyNumber, :policyType, :policyStatus, :effectiveDate, :expiryDate,
                :policyholderId, :agentCode, :branchCode, :totalPremium, :deductible,
                :coverageLimit, :inceptionDate, :renewalCount, :uwStatus, :riskScore,
                :webIndicator, :apiFlag, :lastUpdated, :updatedBy
            )
            """)
        .param("policyNumber", policy.policyNumber())
        .param("policyType", policy.policyType().raw())
        .param("policyStatus", policy.policyStatus().raw())
        .param("effectiveDate", policy.effectiveDate())
        .param("expiryDate", policy.expiryDate())
        .param("policyholderId", policy.policyholderId())
        .param("agentCode", policy.agentCode())
        .param("branchCode", policy.branchCode())
        .param("totalPremium", policy.totalPremium())
        .param("deductible", policy.deductible())
        .param("coverageLimit", policy.coverageLimit())
        .param("inceptionDate", policy.inceptionDate())
        .param("renewalCount", policy.renewalCount())
        .param("uwStatus", policy.uwStatus() == null ? null : policy.uwStatus().raw())
        .param("riskScore", policy.riskScore())
        .param("webIndicator", policy.webIndicator() == null ? null : policy.webIndicator().raw())
        .param("apiFlag", policy.apiFlag() == null ? null : policy.apiFlag().raw())
        .param("lastUpdated", policy.lastUpdated())
        .param("updatedBy", policy.updatedBy())
        .update();
  }

  public Optional<Policy> findByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber")
        .param("policyNumber", policyNumber)
        .query(
            (row, rowNumber) ->
                new Policy(
                    JdbcValues.charValue(row, "policy_number"),
                    JdbcValues.coded(row, "policy_type", PolicyType.class),
                    JdbcValues.coded(row, "policy_status", PolicyStatus.class),
                    JdbcValues.date(row, "effective_date"),
                    JdbcValues.date(row, "expiry_date"),
                    JdbcValues.charValue(row, "policyholder_id"),
                    JdbcValues.charValue(row, "agent_code"),
                    JdbcValues.charValue(row, "branch_code"),
                    row.getBigDecimal("total_premium"),
                    row.getBigDecimal("deductible"),
                    row.getBigDecimal("coverage_limit"),
                    JdbcValues.date(row, "inception_date"),
                    JdbcValues.integer(row, "renewal_count"),
                    JdbcValues.coded(row, "uw_status", UnderwritingStatus.class),
                    JdbcValues.integer(row, "risk_score"),
                    JdbcValues.coded(row, "web_indicator", YesNo.class),
                    JdbcValues.coded(row, "api_flag", YesNo.class),
                    JdbcValues.timestamp(row, "last_updated"),
                    JdbcValues.charValue(row, "updated_by")))
        .optional();
  }

  /**
   * Rewrites the columns POLRNW/POLEND change. Callers hold the row lock from {@code
   * PolicyInquiryRepository.lockPolicy}; anything but one row is a failure so the transaction rolls
   * back (Q-14, Q-16).
   */
  public void saveLifecycle(Policy policy) {
    int rows =
        jdbc.sql(
                "UPDATE pas.policies SET policy_status = :policyStatus, effective_date = :effectiveDate, expiry_date = :expiryDate, total_premium = :totalPremium, coverage_limit = :coverageLimit, renewal_count = :renewalCount, uw_status = :uwStatus, last_updated = now(), updated_by = :updatedBy WHERE policy_number = :policyNumber")
            .param("policyStatus", policy.policyStatus().raw())
            .param("effectiveDate", policy.effectiveDate())
            .param("expiryDate", policy.expiryDate())
            .param("totalPremium", policy.totalPremium())
            .param("coverageLimit", policy.coverageLimit())
            .param("renewalCount", policy.renewalCount())
            .param("uwStatus", policy.uwStatus() == null ? null : policy.uwStatus().raw())
            .param("updatedBy", policy.updatedBy())
            .param("policyNumber", policy.policyNumber())
            .update();
    if (rows != 1) {
      throw new IncorrectUpdateSemanticsDataAccessException(
          "Policy write affected " + rows + " rows");
    }
  }

  /** State of the policyholder, used for the per-state renewal cap (Q-04). */
  public Optional<String> findPolicyholderState(String policyNumber) {
    return jdbc.sql(
            "SELECT h.state_code FROM pas.policies p JOIN pas.policy_holders h ON h.cust_id = p.policyholder_id WHERE p.policy_number = :policyNumber")
        .param("policyNumber", policyNumber)
        .query(String.class)
        .optional()
        .map(String::trim);
  }
}
