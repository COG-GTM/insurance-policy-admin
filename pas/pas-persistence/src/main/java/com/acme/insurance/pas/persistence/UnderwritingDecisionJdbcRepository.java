package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * ACMEINS.UNDERWRITING_DECISIONS, the underwriting audit trail. SQL is portable: rows are
 * identified by (POLICY_NUMBER, DECISION_DATE, CREATED_TIMESTAMP), never by the PostgreSQL-only
 * DECISION_ID (V13, quirk Q-18). On DB2 a second decision for the same policy on the same day still
 * violates the legacy primary key; the insert then fails instead of being silently lost.
 */
public class UnderwritingDecisionJdbcRepository {

  private static final String COLUMNS =
      "policy_number, decision_date, decision_code, risk_score, decision_reason, underwriter_id,"
          + " override_reason, override_by, created_timestamp";

  private final JdbcClient jdbc;

  public UnderwritingDecisionJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the decision; CREATED_TIMESTAMP comes from the database clock. */
  public void insert(UnderwritingDecision d) {
    new Insert("acmeins.underwriting_decisions")
        .set("policy_number", d.policyNumber())
        .set("decision_date", d.decisionDate())
        .set("decision_code", d.decisionCode())
        .set("risk_score", d.riskScore())
        .set("decision_reason", d.decisionReason())
        .set("underwriter_id", d.underwriterId())
        .set("override_reason", d.overrideReason())
        .set("override_by", d.overrideBy())
        .setOrDefault("created_timestamp", d.createdTimestamp())
        .execute(jdbc);
  }

  /** Every decision of the policy, oldest first. */
  public List<UnderwritingDecision> findByPolicy(String policyNumber) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM acmeins.underwriting_decisions WHERE policy_number = ?"
                + " ORDER BY decision_date, created_timestamp")
        .param(policyNumber)
        .query((rs, row) -> map(rs))
        .list();
  }

  /** The latest decision of the policy. */
  public Optional<UnderwritingDecision> findLatest(String policyNumber) {
    List<UnderwritingDecision> all = findByPolicy(policyNumber);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
  }

  /**
   * Stores a manual override on the decision row it overrides. The update only applies while the
   * row still has the decision code and override fields the override was based on, so two
   * concurrent overrides cannot both win, even with the same code; returns the number of rows
   * changed (0 = the row moved on). Callers also lock the policy row ({@code lockPolicy}).
   */
  public int applyOverride(UnderwritingDecision before, UnderwritingDecision after) {
    return jdbc.sql(
            """
            UPDATE acmeins.underwriting_decisions
               SET decision_code = ?, override_reason = ?, override_by = ?
             WHERE policy_number = ? AND decision_date = ? AND created_timestamp = ?
               AND decision_code = ?
               AND COALESCE(override_reason, '') = ? AND COALESCE(override_by, '') = ?
            """)
        .params(
            after.decisionCode(),
            after.overrideReason(),
            after.overrideBy(),
            before.policyNumber(),
            before.decisionDate(),
            before.createdTimestamp(),
            before.decisionCode(),
            before.overrideReason() == null ? "" : before.overrideReason(),
            before.overrideBy() == null ? "" : before.overrideBy())
        .update();
  }

  private static UnderwritingDecision map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new UnderwritingDecision(
        null,
        text(rs, "policy_number"),
        date(rs, "decision_date"),
        text(rs, "decision_code"),
        integer(rs, "risk_score"),
        text(rs, "decision_reason"),
        text(rs, "underwriter_id"),
        text(rs, "override_reason"),
        text(rs, "override_by"),
        timestamp(rs, "created_timestamp"));
  }
}
