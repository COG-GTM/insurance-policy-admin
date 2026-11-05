package com.acme.insurance.pas.policyservice.bridge;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.copybook.PolicyRecordCodec;
import com.acme.insurance.pas.events.EventSchemas;
import com.acme.insurance.pas.events.PasEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns a PAS domain event into the legacy message its consumer still expects: the 125-byte
 * POLICY-RECORD (cobol/copybooks/POLICY-RECORD.cpy) in EBCDIC, built from the current policy row
 * (Q-528-1). Policy created, renewed and endorsed feed the FileNet document trigger with a {@code
 * documentType} header; underwriting requested and decided feed the two underwriting queues.
 */
public class BridgeRouter {

  public static final String DOCUMENT_NEW = "NEW_BUSINESS";
  public static final String DOCUMENT_RENEWAL = "RENEWAL";
  public static final String DOCUMENT_ENDORSEMENT = "ENDORSEMENT";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final BridgeProperties properties;
  private final Function<String, Optional<Policy>> policies;
  private final PolicyRecordCodec codec = new PolicyRecordCodec();

  public BridgeRouter(BridgeProperties properties, Function<String, Optional<Policy>> policies) {
    this.properties = properties;
    this.policies = policies;
  }

  public List<BridgeMessage> route(String topic, String json) {
    PasEventType type =
        PasEventType.fromTopic(topic)
            .orElseThrow(() -> new UnbridgeableEventException("unknown topic " + topic));
    JsonNode event;
    try {
      event = JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new UnbridgeableEventException("not JSON on " + topic);
    }
    int version = event.path("schemaVersion").asInt(-1);
    List<String> violations;
    try {
      violations = EventSchemas.validate(EventSchemas.schema(type, version), event);
    } catch (IllegalArgumentException e) {
      throw new UnbridgeableEventException("unknown schema version " + version + " on " + topic);
    }
    if (!violations.isEmpty()) {
      throw new UnbridgeableEventException(topic + " v" + version + " invalid: " + violations);
    }
    String policyNumber = event.get("policyNumber").asText();
    Policy policy =
        policies
            .apply(policyNumber)
            .orElseThrow(() -> new UnbridgeableEventException("policy not found for " + topic));
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("eventId", event.get("eventId").asText());
    headers.put("eventType", topic);
    String target =
        switch (type) {
          case POLICY_CREATED -> document(headers, DOCUMENT_NEW);
          case POLICY_RENEWED -> document(headers, DOCUMENT_RENEWAL);
          case POLICY_ENDORSED -> document(headers, DOCUMENT_ENDORSEMENT);
          case UNDERWRITING_REQUESTED -> properties.underwritingRequestTopic();
          case UNDERWRITING_DECIDED -> {
            headers.put("decisionCode", event.at("/data/decisionCode").asText());
            headers.put("referral", event.at("/data/referral").asText());
            yield properties.underwritingDecisionTopic();
          }
        };
    return List.of(new BridgeMessage(target, policyNumber, codec.encode(policy), headers));
  }

  private String document(Map<String, String> headers, String documentType) {
    headers.put("documentType", documentType);
    return properties.documentTriggerTopic();
  }
}
