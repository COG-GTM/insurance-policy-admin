package com.acme.insurance.pas.policy.events;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DocumentRequests {
  private final OutboxWriter outbox;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "OutboxWriter is a Spring-managed collaborator.")
  public DocumentRequests(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  public UUID append(
      String policyNumber,
      long policyVersion,
      String correlationId,
      DocumentType type,
      UUID triggeringEventId) {
    return append(
        policyNumber,
        policyVersion,
        correlationId,
        type,
        triggeringEventId,
        type.triggeringEventType());
  }

  public UUID append(
      String policyNumber,
      long policyVersion,
      String correlationId,
      DocumentType type,
      UUID triggeringEventId,
      String triggeringEventType) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("documentType", type.name());
    data.put("triggeringEventId", triggeringEventId);
    data.put("triggeringEventType", triggeringEventType);
    return outbox.append(
        OutboxWriter.DOCUMENT_TOPIC,
        "DocumentRequested",
        policyNumber,
        policyVersion,
        correlationId,
        data);
  }

  public enum DocumentType {
    NEW_BUSINESS("PolicyCreated"),
    RENEWAL("PolicyRenewed"),
    ENDORSEMENT("PolicyEndorsed"),
    UNDERWRITING_DECISION(null);

    private final String triggeringEventType;

    DocumentType(String triggeringEventType) {
      this.triggeringEventType = triggeringEventType;
    }

    public String triggeringEventType() {
      return triggeringEventType;
    }
  }
}
