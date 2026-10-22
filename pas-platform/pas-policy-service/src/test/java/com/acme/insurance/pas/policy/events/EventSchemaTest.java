package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventSchemaTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void validatesEventTypesAndRejectsUnexpectedPiiProperties() throws Exception {
    Map<String, Map<String, Object>> events = new LinkedHashMap<>();
    events.put(
        "PolicyCreated",
        Map.ofEntries(
            Map.entry("policyType", "AUT"),
            Map.entry("policyStatus", "PN"),
            Map.entry("uwStatus", "PN"),
            Map.entry("effectiveDate", "2025-01-01"),
            Map.entry("expiryDate", "2026-01-01"),
            Map.entry("agentCode", "AGT001"),
            Map.entry("branchCode", "B001"),
            Map.entry("coverageLimit", 100000),
            Map.entry("deductible", 100),
            Map.entry("totalPremium", 500),
            Map.entry("coverages", List.of(Map.of("sequenceNum", 1, "coverageType", "AUTL")))));
    events.put(
        "UnderwritingReferralRequested",
        Map.of(
            "trigger",
            "NEW_BUSINESS",
            "decisionId",
            new NullableValue(null),
            "decisionCode",
            new NullableValue(null),
            "riskScore",
            new NullableValue(null),
            "reasons",
            new NullableValue(null),
            "lossRatio",
            new NullableValue(null),
            "override",
            new NullableValue(null),
            "overridesDecisionId",
            new NullableValue(null)));
    events.put(
        "UnderwritingReferralRequestedRenewal",
        Map.of(
            "trigger",
            "RENEWAL",
            "decisionId",
            new NullableValue(null),
            "decisionCode",
            new NullableValue(null),
            "riskScore",
            new NullableValue(null),
            "reasons",
            new NullableValue(null),
            "lossRatio",
            new NullableValue(null),
            "override",
            new NullableValue(null),
            "overridesDecisionId",
            new NullableValue(null)));
    events.put(
        "UnderwritingDecisionRecorded",
        Map.of(
            "decisionId",
            1,
            "decisionCode",
            "AP",
            "riskScore",
            250,
            "reasons",
            List.of("AUTO-ACCEPTED: LOW RISK"),
            "lossRatio",
            0,
            "override",
            false,
            "overridesDecisionId",
            new NullableValue(null)));
    events.put(
        "PolicyRenewed",
        Map.ofEntries(
            Map.entry("priorExpiryDate", "2025-01-01"),
            Map.entry("effectiveDate", "2025-01-01"),
            Map.entry("expiryDate", "2026-01-01"),
            Map.entry("renewalCount", 1),
            Map.entry("policyStatus", "AC"),
            Map.entry("uwStatus", "PN"),
            Map.entry("oldPremium", 100),
            Map.entry("newPremium", 105),
            Map.entry("rateChangePct", 5),
            Map.entry("capped", false)));
    events.put(
        "PolicyEndorsed",
        Map.of(
            "endorsementSequence",
            1,
            "endorsementType",
            "ADD",
            "effectiveDate",
            "2025-06-01",
            "premiumAdjustment",
            25,
            "totalPremium",
            125,
            "policyStatus",
            "AC"));
    events.put(
        "DocumentRequested",
        Map.of(
            "documentType", "RENEWAL",
            "triggeringEventId", "fc2843f3-fdfa-4bf9-8f89-3f6af4ba33ce",
            "triggeringEventType", "PolicyRenewed"));

    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    for (Map.Entry<String, Map<String, Object>> entry : events.entrySet()) {
      String eventType =
          entry.getKey().equals("UnderwritingReferralRequestedRenewal")
              ? "UnderwritingReferralRequested"
              : entry.getKey();
      JsonSchema schema = factory.getSchema(schemaPath(eventType).toUri());
      JsonNode event = mapper.valueToTree(envelope(eventType, entry.getValue()));
      assertThat(schema.validate(event)).isEmpty();
      ObjectNode eventWithPii = (ObjectNode) event.deepCopy();
      ((ObjectNode) eventWithPii.get("data")).put("policyholderId", "CUST000001");
      assertThat(schema.validate(eventWithPii)).isNotEmpty();
      assertThat(propertyNames(schemaPath(eventType)))
          .noneMatch(
              name ->
                  name.matches("(?i).*(holder|name|credit|tax|ssn|email|phone|address|birth).*"));
    }
  }

  private Map<String, Object> envelope(String eventType, Map<String, Object> data) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", "fc2843f3-fdfa-4bf9-8f89-3f6af4ba33ce");
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("policyNumber", "POL000100000");
    envelope.put("policyVersion", 1);
    envelope.put("occurredAt", "2025-01-01T00:00:00Z");
    envelope.put("correlationId", "test-correlation");
    Map<String, Object> envelopeData = new LinkedHashMap<>();
    data.forEach(
        (key, value) ->
            envelopeData.put(
                key, value instanceof NullableValue nullable ? nullable.value() : value));
    envelope.put("data", envelopeData);
    return envelope;
  }

  private static Path schemaPath(String eventType) {
    return Path.of("../events/schemas/v1", eventType + ".schema.json");
  }

  private Set<String> propertyNames(Path schemaPath) throws Exception {
    JsonNode schema = mapper.readTree(Files.readString(schemaPath));
    return propertyNames(schema);
  }

  private Set<String> propertyNames(JsonNode node) {
    java.util.Set<String> names = new java.util.HashSet<>();
    if (node.has("properties")) {
      node.get("properties").fieldNames().forEachRemaining(names::add);
    }
    node.elements().forEachRemaining(child -> names.addAll(propertyNames(child)));
    node.fields().forEachRemaining(entry -> names.addAll(propertyNames(entry.getValue())));
    return names;
  }

  private record NullableValue(Object value) {}
}
