package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.decimal;
import static com.acme.insurance.pas.persistence.Columns.integer;
import static com.acme.insurance.pas.persistence.Columns.text;
import static com.acme.insurance.pas.persistence.Columns.timestamp;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PASAPP.POLICY_CHANGE_AUDIT: append-only, written in the same transaction as the change. */
public class PolicyChangeAuditJdbcRepository {

  private final JdbcClient jdbc;

  public PolicyChangeAuditJdbcRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PolicyChangeAudit a) {
    new Insert("pasapp.policy_change_audit")
        .set("policy_number", a.policyNumber())
        .set("operation", a.operation().name())
        .set("legacy_program", a.legacyProgram())
        .set("endorsement_seq", a.endorsementSeq())
        .set("endorsement_type", a.endorsementType())
        .set("principal", a.principal())
        .set("client_id", a.clientId())
        .set("correlation_id", a.correlationId())
        .set("policy_status_before", a.policyStatusBefore())
        .set("policy_status_after", a.policyStatusAfter())
        .set("effective_date_before", a.effectiveDateBefore())
        .set("expiry_date_before", a.expiryDateBefore())
        .set("effective_date_after", a.effectiveDateAfter())
        .set("expiry_date_after", a.expiryDateAfter())
        .set("premium_before", a.premiumBefore())
        .set("premium_adjustment", a.premiumAdjustment())
        .set("premium_after", a.premiumAfter())
        .set("renewal_count_after", a.renewalCountAfter())
        .set("occurred_at", a.occurredAt())
        .execute(jdbc);
  }

  public List<PolicyChangeAudit> findByPolicy(String policyNumber) {
    return jdbc.sql(
            "SELECT * FROM pasapp.policy_change_audit WHERE policy_number = ? ORDER BY audit_id")
        .param(policyNumber)
        .query(
            (rs, row) ->
                new PolicyChangeAudit(
                    text(rs, "policy_number"),
                    PolicyChangeAudit.Operation.valueOf(text(rs, "operation")),
                    text(rs, "legacy_program"),
                    integer(rs, "endorsement_seq"),
                    text(rs, "endorsement_type"),
                    text(rs, "principal"),
                    text(rs, "client_id"),
                    text(rs, "correlation_id"),
                    text(rs, "policy_status_before"),
                    text(rs, "policy_status_after"),
                    date(rs, "effective_date_before"),
                    date(rs, "expiry_date_before"),
                    date(rs, "effective_date_after"),
                    date(rs, "expiry_date_after"),
                    decimal(rs, "premium_before"),
                    decimal(rs, "premium_adjustment"),
                    decimal(rs, "premium_after"),
                    integer(rs, "renewal_count_after"),
                    timestamp(rs, "occurred_at")))
        .list();
  }
}
