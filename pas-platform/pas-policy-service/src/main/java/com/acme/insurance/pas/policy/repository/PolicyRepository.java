package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyInquiryRepository;
import java.math.BigDecimal;
import java.util.Optional;
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
                web_indicator, api_flag, last_updated, updated_by, row_version
            ) VALUES (
                :policyNumber, :policyType, :policyStatus, :effectiveDate, :expiryDate,
                :policyholderId, :agentCode, :branchCode, :totalPremium, :deductible,
                :coverageLimit, :inceptionDate, :renewalCount, :uwStatus, :riskScore,
                :webIndicator, :apiFlag, :lastUpdated, :updatedBy, 1
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

  public boolean insertIfAbsent(Policy policy) {
    return jdbc.sql(
                """
            INSERT INTO pas.policies (
                policy_number, policy_type, policy_status, effective_date, expiry_date,
                policyholder_id, agent_code, branch_code, total_premium, deductible,
                coverage_limit, inception_date, renewal_count, uw_status, risk_score,
                web_indicator, api_flag, last_updated, updated_by, row_version
            ) VALUES (
                :policyNumber, :policyType, :policyStatus, :effectiveDate, :expiryDate,
                :policyholderId, :agentCode, :branchCode, :totalPremium, :deductible,
                :coverageLimit, :inceptionDate, :renewalCount, :uwStatus, :riskScore,
                :webIndicator, :apiFlag, :lastUpdated, :updatedBy, 1
            )
            ON CONFLICT (policy_number) DO NOTHING
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
            .param(
                "webIndicator", policy.webIndicator() == null ? null : policy.webIndicator().raw())
            .param("apiFlag", policy.apiFlag() == null ? null : policy.apiFlag().raw())
            .param("lastUpdated", policy.lastUpdated())
            .param("updatedBy", policy.updatedBy())
            .update()
        == 1;
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

  public Optional<Policy> findForUpdate(String policyNumber, CallerEntitlements entitlements) {
    return jdbc.sql(
            """
            SELECT policy_number, policy_type, policy_status, effective_date, expiry_date,
                   policyholder_id, agent_code, branch_code, total_premium, deductible,
                   coverage_limit, inception_date, renewal_count, uw_status, risk_score,
                   web_indicator, api_flag, last_updated, updated_by
            FROM pas.policies p
            WHERE p.policy_number = :policyNumber AND
            """
                + PolicyInquiryRepository.entitlementPredicate("p")
                + " FOR UPDATE")
        .param("policyNumber", policyNumber)
        .params(PolicyInquiryRepository.entitlementParameters(entitlements))
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

  public long updateUnderwriting(
      String policyNumber, String status, int riskScore, String updatedBy) {
    return jdbc.sql(
            """
            UPDATE pas.policies
            SET uw_status = :status, risk_score = :riskScore, updated_by = :updatedBy,
                row_version = row_version + 1, last_updated = now()
            WHERE policy_number = :policyNumber
            RETURNING row_version
            """)
        .param("status", status)
        .param("riskScore", riskScore)
        .param("updatedBy", updatedBy)
        .param("policyNumber", policyNumber)
        .query(Long.class)
        .single();
  }

  public BigDecimal branchAccumulation(String branchCode) {
    return jdbc.sql(
            """
            SELECT COALESCE(SUM(coverage_limit), 0)
            FROM pas.policies
            WHERE branch_code = :branchCode AND policy_status = 'AC'
            """)
        .param("branchCode", branchCode)
        .query(BigDecimal.class)
        .single();
  }
}
