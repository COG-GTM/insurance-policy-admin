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
  private static final int LEGACY_DECISION_REASON_MAX_LENGTH = 100;

  private static final String SELECT =
      """
      SELECT decision_id, policy_number, decision_date, decision_code, risk_score, decision_reason,
             decision_reasons, loss_ratio, underwriter_id, decided_by, override_reason, override_by,
             overrides_decision_id, created_timestamp
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
        .param("decisionReason", legacyDecisionReason(decision.decisionReason()))
        .param("underwriterId", decision.underwriterId())
        .param("overrideReason", decision.overrideReason())
        .param("overrideBy", decision.overrideBy())
        .param("createdTimestamp", decision.createdTimestamp())
        .update();
  }

  public long insert(UnderwritingDecisionAudit decision) {
    return jdbc.sql(
            """
            INSERT INTO pas.underwriting_decisions (
                policy_number, decision_date, decision_code, risk_score, decision_reason,
                decision_reasons, loss_ratio, underwriter_id, decided_by, override_reason, override_by,
                overrides_decision_id, created_timestamp
            ) VALUES (
                :policyNumber, :decisionDate, :decisionCode, :riskScore, :decisionReason,
                CAST(:reasons AS text[]), :lossRatio, :underwriterId, :decidedBy, :overrideReason,
                :overrideBy, :overridesDecisionId, :createdTimestamp
            )
            RETURNING decision_id
            """)
        .param("policyNumber", decision.policyNumber())
        .param("decisionDate", decision.decisionDate())
        .param("decisionCode", decision.decisionCode())
        .param("riskScore", decision.riskScore())
        .param("decisionReason", legacyDecisionReason(decision.decisionReason()))
        .param("reasons", decision.reasons().toArray(String[]::new))
        .param("lossRatio", decision.lossRatio())
        .param("underwriterId", decision.underwriterId())
        .param("decidedBy", decision.decidedBy())
        .param("overrideReason", decision.overrideReason())
        .param("overrideBy", decision.overrideBy())
        .param("overridesDecisionId", decision.overridesDecisionId())
        .param("createdTimestamp", decision.createdTimestamp())
        .query(Long.class)
        .single();
  }

  public Optional<UnderwritingDecision> findByKey(String policyNumber, LocalDate decisionDate) {
    return jdbc.sql(
            SELECT
                + " WHERE policy_number = :policyNumber AND decision_date = :decisionDate"
                + " ORDER BY decision_id DESC LIMIT 1")
        .param("policyNumber", policyNumber)
        .param("decisionDate", decisionDate)
        .query((row, rowNumber) -> map(row))
        .optional();
  }

  public List<UnderwritingDecision> findByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber ORDER BY decision_id DESC")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> map(row))
        .list();
  }

  public Optional<UnderwritingDecisionAudit> findById(String policyNumber, long decisionId) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber AND decision_id = :decisionId")
        .param("policyNumber", policyNumber)
        .param("decisionId", decisionId)
        .query((row, rowNumber) -> mapAudit(row))
        .optional();
  }

  public Optional<UnderwritingDecisionAudit> findLatest(String policyNumber) {
    return jdbc.sql(
            SELECT + " WHERE policy_number = :policyNumber ORDER BY decision_id DESC LIMIT 1")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> mapAudit(row))
        .optional();
  }

  public List<UnderwritingDecisionAudit> findAuditRecordsByPolicyNumber(String policyNumber) {
    return jdbc.sql(SELECT + " WHERE policy_number = :policyNumber ORDER BY decision_id DESC")
        .param("policyNumber", policyNumber)
        .query((row, rowNumber) -> mapAudit(row))
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

  private static UnderwritingDecisionAudit mapAudit(java.sql.ResultSet row)
      throws java.sql.SQLException {
    java.sql.Array reasons = row.getArray("decision_reasons");
    String[] values;
    if (reasons == null) {
      String reason = row.getString("decision_reason");
      values = reason == null ? new String[0] : new String[] {reason};
    } else {
      values = (String[]) reasons.getArray();
    }
    return new UnderwritingDecisionAudit(
        row.getLong("decision_id"),
        JdbcValues.charValue(row, "policy_number"),
        JdbcValues.date(row, "decision_date"),
        JdbcValues.charValue(row, "decision_code"),
        JdbcValues.integer(row, "risk_score"),
        row.getString("decision_reason"),
        List.of(values),
        row.getBigDecimal("loss_ratio"),
        JdbcValues.charValue(row, "underwriter_id"),
        row.getString("decided_by"),
        JdbcValues.timestamp(row, "created_timestamp"),
        row.getObject("overrides_decision_id", Long.class),
        row.getString("override_reason"),
        JdbcValues.charValue(row, "override_by"));
  }

  static String legacyDecisionReason(String reason) {
    return reason != null
            && reason.codePointCount(0, reason.length()) > LEGACY_DECISION_REASON_MAX_LENGTH
        ? reason.substring(0, reason.offsetByCodePoints(0, LEGACY_DECISION_REASON_MAX_LENGTH))
        : reason;
  }
}
