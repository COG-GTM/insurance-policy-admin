package com.acme.insurance.pas.policy.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable audit trail (Q-15) written inside the business transaction, replacing POLEND's
 * ENDORSEMENT-AUDIT temporary-storage queue. {@code actor} is the JWT {@code sub}; details must not
 * contain PII.
 */
@Repository
public class PolicyAuditRepository {
  public static final String RENEWAL = "RENEWAL";
  public static final String ENDORSEMENT = "ENDORSEMENT";

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed ObjectMapper is shared by design.")
  public PolicyAuditRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  public void record(
      String policyNumber,
      String action,
      String reference,
      String actor,
      BigDecimal premiumBefore,
      BigDecimal premiumAfter,
      Map<String, ?> details) {
    jdbc.sql(
            """
            INSERT INTO pas.policy_audit (
                policy_number, action, reference, actor, total_premium_before,
                total_premium_after, details
            ) VALUES (
                :policyNumber, :action, :reference, :actor, :before, :after,
                CAST(:details AS jsonb)
            )
            """)
        .param("policyNumber", policyNumber)
        .param("action", action)
        .param("reference", reference)
        .param("actor", actor)
        .param("before", premiumBefore)
        .param("after", premiumAfter)
        .param("details", json(details))
        .update();
  }

  private String json(Map<String, ?> details) {
    try {
      return objectMapper.writeValueAsString(details == null ? Map.of() : details);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Audit details are not serialisable", exception);
    }
  }
}
