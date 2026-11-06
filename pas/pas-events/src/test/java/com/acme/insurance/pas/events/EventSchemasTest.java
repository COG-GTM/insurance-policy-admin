package com.acme.insurance.pas.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventSchemasTest {

  static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void releasedSchemaFilesAreUnchanged() throws Exception {
    for (JsonNode entry : EventSchemas.registry().get("schemas")) {
      String file = "/schemas/" + entry.get("file").asText();
      try (InputStream in = EventSchemas.class.getResourceAsStream(file)) {
        assertThat(in).as(file).isNotNull();
        String digest =
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        assertThat(digest)
            .as("%s is released and immutable; add a new version instead", file)
            .isEqualTo(entry.get("sha256").asText());
      }
    }
  }

  @Test
  void everyEventTypeHasASchemaForTheCurrentVersion() {
    for (PasEventType type : PasEventType.values()) {
      JsonNode schema = EventSchemas.schema(type, PasEvent.SCHEMA_VERSION);
      assertThat(schema.at("/properties/eventType/const").asText()).isEqualTo(type.topic());
    }
  }

  @Test
  void everyProducedEventConformsToItsSchema() {
    for (PasEvent event : samples()) {
      assertThat(
              EventSchemas.validate(event.type(), event.schemaVersion(), PasEvents.toJson(event)))
          .as(event.type().topic())
          .isEmpty();
    }
  }

  @Test
  void schemasRejectUnknownAndMissingFields() throws Exception {
    PasEvent event = samples().get(1);
    ObjectNode json = (ObjectNode) JSON.readTree(PasEvents.toJson(event));
    ((ObjectNode) json.get("data")).put("policyholderName", "JANE DOE");
    ((ObjectNode) json.get("data")).remove("renewalCount");
    json.put("schemaVersion", 2);
    List<String> errors = EventSchemas.validate(EventSchemas.schema(event.type(), 1), json);
    assertThat(errors)
        .anyMatch(e -> e.contains("policyholderName: not allowed"))
        .anyMatch(e -> e.contains("missing renewalCount"))
        .anyMatch(e -> e.contains("schemaVersion"));
  }

  static List<PasEvent> samples() {
    Policy before = PasEventsTest.policy();
    Policy after =
        new Policy(
            before.policyNumber(),
            before.policyTypeCode(),
            "AC",
            before.expiryDate(),
            before.expiryDate().plusYears(1),
            before.policyholderId(),
            before.agentCode(),
            before.branchCode(),
            new BigDecimal("1100.00"),
            before.deductible(),
            before.coverageLimit(),
            before.inceptionDate(),
            1,
            "PN",
            before.riskScore(),
            before.webIndicator(),
            before.apiFlag(),
            before.lastUpdated(),
            before.updatedBy());
    UnderwritingDecision decision =
        new UnderwritingDecision(
            null,
            before.policyNumber(),
            LocalDate.of(2026, 12, 1),
            "RF",
            450,
            "REFERRED",
            "SYSTEM",
            null,
            null,
            null);
    PasEvents events = PasEventsTest.EVENTS;
    return List.of(
        events.policyCreated(before, PasEventsTest.coverages(), "c-1"),
        events.policyRenewed(PolicyRenewed.of(before, after), "c-2"),
        events.policyEndorsed(
            PolicyEndorsed.of(
                after,
                1,
                "CAD",
                LocalDate.of(2026, 6, 1),
                new BigDecimal("12.34"),
                new BigDecimal("1112.34")),
            null),
        events.underwritingRequested(before, "c-4"),
        events.underwritingDecided(decision, "c-5"));
  }
}
