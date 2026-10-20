package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.support.BusinessCalendar;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code pas.policy_audit} (Flyway V8), the durable replacement for POLEND's {@code
 * ENDORSEMENT-AUDIT} CICS temporary-storage queue (ADR 0001). Runs inside the business transaction,
 * so an audit row exists exactly when the change committed.
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class PolicyAuditWriter {

  public static final String ACTION_RENEWAL = "RENEWAL";
  public static final String ACTION_ENDORSEMENT = "ENDORSEMENT";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final BusinessCalendar calendar;

  public PolicyAuditWriter(JdbcTemplate jdbc, ObjectMapper mapper, BusinessCalendar calendar) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.calendar = calendar;
  }

  /** {@code details} is serialised to JSON; it must not contain policyholder PII. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      String policyNumber,
      String action,
      String program,
      long policyVersion,
      String actor,
      String correlationId,
      Object details) {
    String json;
    try {
      json = mapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("audit details not serialisable", e);
    }
    jdbc.update(
        "INSERT INTO pas.policy_audit (policy_number, action, program, policy_version, actor,"
            + " correlation_id, occurred_at, details) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
        policyNumber,
        action,
        program,
        policyVersion,
        actor,
        correlationId,
        Timestamp.valueOf(calendar.nowUtc()),
        json);
  }
}
