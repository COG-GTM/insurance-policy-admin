package com.acme.insurance.pas.policy.repository;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.domain.UnderwritingDecisionCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UnderwritingDecisionRepository {
  private static final String SELECT =
      """
      SELECT policy_number, decision_date, decision_code, risk_score, decision_reason,
             underwriter_id, override_reason, override_by, created_timestamp
      FROM pas.underwriting_decisions
      """;

  private final JdbcClient jdbc;

  public UnderwritingDecisionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UnderwritingDecision decision) {
    jdbc.sql(
            """
            INSERT INTO pas.underwriting_decisions (
                policy_number, decision_date, decision_code, risk_score, decision_reason,
                underwriter_id, override_reason, override_by, created_timestamp
            ) VALUES (
                :policyNumber, :decisionDate, :decisionCode, :riskScore, :decisionReason,
                :underwriterId, :overrideReason, :overrideBy, :createdTimestamp
            )
            """)
        .param("policyNumber", decision.policyNumber())
        .param("decisionDate", decision.decisionDate())
        .param("decisionCode", decision.decisionCode().raw())
        .param("riskScore", decision.riskScore())
        .param("decisionReason", decision.decisionReason())
        .param("underwriterId", decision.underwriterId())
        .param("overrideReason", decision.overrideReason())
        .param("overrideBy", decision.overrideBy())
        .param("createdTimestamp", decision.createdTimestamp())
        .update();
  }

  public Optional<UnderwritingDecision> findByKey(String policyNumber, LocalDate decisionDate) {
    return jdbc.sql(
            SELECT + " WHERE policy_number = :policyNumber AND decision_date = :decisionDate")
        .param("policyNumber", policyNumber)
        .param("decisionDate", decisionDate)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  public List<UnderwritingDecision> findByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber ORDER BY decision_date")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> map(row))
        .list();
  }

  private static UnderwritingDecision map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new UnderwritingDecision(
        JdbcValues.charValue(row, "policy_number"),
        JdbcValues.date(row, "decision_date"),
        JdbcValues.coded(row, "decision_code", UnderwritingDecisionCode.class),
        JdbcValues.integer(row, "risk_score"),
        row.getString("decision_reason"),
        JdbcValues.charValue(row, "underwriter_id"),
        row.getString("override_reason"),
        JdbcValues.charValue(row, "override_by"),
        JdbcValues.timestamp(row, "created_timestamp"));
  }
}
