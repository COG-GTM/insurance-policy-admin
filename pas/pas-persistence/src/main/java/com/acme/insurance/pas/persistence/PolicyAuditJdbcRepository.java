package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PASAPP.POLICY_AUDIT (V51). Insert and read only: the table is append-only (trigger on PostgreSQL,
 * grants on DB2).
 */
public class PolicyAuditJdbcRepository {

  static final int PRINCIPAL_LENGTH = 256;
  static final int DETAILS_LENGTH = 8000;

  private final JdbcClient jdbc;

  public PolicyAuditJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void append(PolicyAuditEntry e) {
    if (e.details().length() > DETAILS_LENGTH) {
      throw new IllegalArgumentException("audit details exceed " + DETAILS_LENGTH + " characters");
    }
    String principal = e.principal();
    if (principal.length() > PRINCIPAL_LENGTH) {
      principal = principal.substring(0, PRINCIPAL_LENGTH);
    }
    new Insert("pasapp.policy_audit")
        .setOrDefault("occurred_at", e.occurredAt())
        .set("action", e.action())
        .set("policy_number", e.policyNumber())
        .set("principal", principal)
        .set("client_id", e.clientId())
        .set("legacy_user", e.legacyUser())
        .set("correlation_id", e.correlationId())
        .set("details", e.details())
        .execute(jdbc);
  }

  public List<PolicyAuditEntry> findByPolicy(String policyNumber) {
    return jdbc.sql("SELECT * FROM pasapp.policy_audit WHERE policy_number = ? ORDER BY audit_id")
        .param(policyNumber)
        .query(
            (rs, n) ->
                new PolicyAuditEntry(
                    rs.getLong("audit_id"),
                    timestamp(rs, "occurred_at"),
                    text(rs, "action"),
                    text(rs, "policy_number"),
                    rs.getString("principal"),
                    rs.getString("client_id"),
                    text(rs, "legacy_user"),
                    rs.getString("correlation_id"),
                    rs.getString("details")))
        .list();
  }
}
