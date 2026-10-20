package com.acme.insurance.pas.policy.events;

import com.acme.insurance.pas.common.domain.PolicyRecord;
import com.acme.insurance.pas.policy.persistence.CopybookMapping;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code pas.policy_versions} (V9): the policy row as committed at each {@code policyVersion}, so
 * consumers rebuild {@code POLICY-RECORD} for the exact version an event names (ADR 0001 D4).
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class PolicyVersionSnapshots {

  private static final String COLUMNS =
      "policy_number, policy_version, policy_type, policy_status, effective_date, expiry_date,"
          + " policyholder_id, agent_code, branch_code, total_premium, deductible, coverage_limit,"
          + " inception_date, renewal_count, uw_status, risk_score, web_indicator, api_flag,"
          + " last_updated, updated_by";

  private final JdbcTemplate jdbc;

  public PolicyVersionSnapshots(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Records {@code policy} at its current version; idempotent for the same version. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void capture(PolicyEntity p) {
    jdbc.update(
        "INSERT INTO pas.policy_versions ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (policy_number, policy_version) DO NOTHING",
        p.getPolicyNumber().strip(),
        p.getVersion(),
        p.getPolicyType(),
        p.getPolicyStatus(),
        p.getEffectiveDate(),
        p.getExpiryDate(),
        p.getPolicyholderId(),
        p.getAgentCode(),
        p.getBranchCode(),
        p.getTotalPremium(),
        p.getDeductible(),
        p.getCoverageLimit(),
        p.getInceptionDate(),
        p.getRenewalCount(),
        p.getUwStatus(),
        p.getRiskScore(),
        p.getWebIndicator(),
        p.getApiFlag(),
        p.getLastUpdated(),
        p.getUpdatedBy());
  }

  /** The {@code POLICY-RECORD} view of the policy at {@code version}, if it was captured. */
  public Optional<PolicyRecord> find(String policyNumber, long version) {
    List<PolicyEntity> rows =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM pas.policy_versions"
                + " WHERE policy_number = ? AND policy_version = ?",
            (rs, n) -> entity(rs),
            policyNumber.strip(),
            version);
    return rows.stream().findFirst().map(CopybookMapping::toRecord);
  }

  private static PolicyEntity entity(ResultSet rs) throws SQLException {
    PolicyEntity p = new PolicyEntity();
    p.setPolicyNumber(rs.getString("policy_number"));
    p.setPolicyType(rs.getString("policy_type"));
    p.setPolicyStatus(rs.getString("policy_status"));
    p.setEffectiveDate(rs.getObject("effective_date", java.time.LocalDate.class));
    p.setExpiryDate(rs.getObject("expiry_date", java.time.LocalDate.class));
    p.setPolicyholderId(rs.getString("policyholder_id"));
    p.setAgentCode(rs.getString("agent_code"));
    p.setBranchCode(rs.getString("branch_code"));
    p.setTotalPremium(rs.getBigDecimal("total_premium"));
    p.setDeductible(rs.getBigDecimal("deductible"));
    p.setCoverageLimit(rs.getBigDecimal("coverage_limit"));
    p.setInceptionDate(rs.getObject("inception_date", java.time.LocalDate.class));
    p.setRenewalCount(shortOrNull(rs, "renewal_count"));
    p.setUwStatus(rs.getString("uw_status"));
    p.setRiskScore(shortOrNull(rs, "risk_score"));
    p.setWebIndicator(rs.getString("web_indicator"));
    p.setApiFlag(rs.getString("api_flag"));
    p.setLastUpdated(rs.getObject("last_updated", java.time.LocalDateTime.class));
    p.setUpdatedBy(rs.getString("updated_by"));
    return p;
  }

  private static Short shortOrNull(ResultSet rs, String column) throws SQLException {
    short v = rs.getShort(column);
    return rs.wasNull() ? null : v;
  }
}
