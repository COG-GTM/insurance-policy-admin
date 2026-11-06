package com.acme.insurance.pas.policyservice.audit;

import com.acme.insurance.pas.persistence.PolicyAuditEntry;
import com.acme.insurance.pas.persistence.PolicyAuditJdbcRepository;
import com.acme.insurance.pas.policyservice.security.CallerIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Writes PASAPP.POLICY_AUDIT (ADR 0001 D5, DS-05) inside the business transaction: action, policy,
 * the full principal and the before/after values of the changed fields.
 */
@Component
public class AuditWriter {

  public static final String POLICY_CREATED = "POLICY_CREATED";
  public static final String UNDERWRITING_DECIDED = "UNDERWRITING_DECIDED";
  public static final String UNDERWRITING_OVERRIDDEN = "UNDERWRITING_OVERRIDDEN";

  private final PolicyAuditJdbcRepository audit;
  private final ObjectMapper json;

  public AuditWriter(PolicyAuditJdbcRepository audit, ObjectMapper json) {
    this.audit = audit;
    this.json = json;
  }

  public void record(
      String action,
      String policyNumber,
      CallerIdentity caller,
      String correlationId,
      Map<String, ?> before,
      Map<String, ?> after) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("before", before);
    details.put("after", after);
    String body;
    try {
      body = json.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("audit details cannot be serialised", e);
    }
    audit.append(
        new PolicyAuditEntry(
            null,
            null,
            action,
            policyNumber,
            caller.principal(),
            caller.clientId(),
            caller.legacyUser(),
            correlationId,
            body));
  }
}
