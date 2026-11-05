package com.acme.insurance.pas.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasEventsTest {

  static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000525");
  static final PasEvents EVENTS =
      new PasEvents(Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC), () -> ID);
  static final ObjectMapper JSON = new ObjectMapper();

  static Policy policy() {
    return new Policy(
        "POL-01000000",
        "AUT",
        "PN",
        LocalDate.of(2026, 12, 1),
        LocalDate.of(2027, 12, 1),
        "C000000001",
        "AG0001",
        "B001",
        new BigDecimal("1250.50"),
        new BigDecimal("500.00"),
        new BigDecimal("300000.00"),
        LocalDate.of(2026, 12, 1),
        0,
        "PN",
        0,
        "N",
        "N",
        null,
        "POLNEW");
  }

  static List<Coverage> coverages() {
    return List.of(
        new Coverage(
            "POL-01000000",
            1,
            "AUTL",
            "Auto Liability",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2027, 12, 1),
            "AC",
            100,
            null,
            null));
  }

  @Test
  void policyCreatedEnvelope() throws Exception {
    OutboxMessage message =
        PasEvents.toOutboxMessage(EVENTS.policyCreated(policy(), coverages(), "corr-1"));
    assertThat(message.topic()).isEqualTo("pas.policy.created");
    assertThat(message.key()).isEqualTo("POL-01000000");
    assertThat(message.eventId()).isEqualTo(ID.toString());
    JsonNode json = JSON.readTree(message.payload());
    assertThat(json.get("eventType").asText()).isEqualTo("pas.policy.created");
    assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-05T12:00:00Z");
    assertThat(json.get("correlationId").asText()).isEqualTo("corr-1");
    JsonNode data = json.get("data");
    assertThat(data.get("effectiveDate").asText()).isEqualTo("2026-12-01");
    assertThat(data.get("totalPremium").decimalValue()).isEqualByComparingTo("1250.50");
    assertThat(message.payload()).contains("\"totalPremium\":1250.50");
    assertThat(data.get("coverages").get(0).get("coverageType").asText()).isEqualTo("AUTL");
    assertThat(data.has("lastName")).isFalse();
  }

  @Test
  void underwritingEventsCarryTheLegacyQueueTheyReplace() throws Exception {
    PasEvent requested = EVENTS.underwritingRequested(policy(), null);
    assertThat(requested.type().legacyQueue()).contains("ACME.PAS.UNDERWRITING.REQUEST");
    assertThat(JSON.readTree(PasEvents.toJson(requested)).get("data").get("uwStatus").asText())
        .isEqualTo("PN");

    UnderwritingDecision decision =
        new UnderwritingDecision(
            null,
            "POL-01000000",
            LocalDate.of(2026, 12, 1),
            "RS",
            1450,
            "REFERRED TO SENIOR UNDERWRITER",
            "SYSTEM",
            null,
            null,
            null);
    PasEvent decided = EVENTS.underwritingDecided(decision, "c");
    assertThat(decided.type().legacyQueue()).contains("ACME.PAS.UNDERWRITING.DECISION");
    JsonNode data = JSON.readTree(PasEvents.toJson(decided)).get("data");
    assertThat(data.get("riskScore").asInt()).isEqualTo(1450);
    assertThat(data.get("referral").asBoolean()).isTrue();
    assertThat(data.get("override").asBoolean()).isFalse();
    assertThat(data.get("decidedBy").asText()).isEqualTo("SYSTEM");

    UnderwritingDecision overridden =
        new UnderwritingDecision(
            null,
            "POL-01000000",
            LocalDate.of(2026, 12, 1),
            "AP",
            1450,
            "REFERRED TO SENIOR UNDERWRITER",
            "SYSTEM",
            "free text",
            "UW00042",
            null);
    JsonNode o = JSON.readTree(PasEvents.toJson(EVENTS.underwritingDecided(overridden, "c")));
    assertThat(o.get("data").get("override").asBoolean()).isTrue();
    assertThat(o.get("data").get("decidedBy").asText()).isEqualTo("UW00042");
    assertThat(o.toString()).doesNotContain("free text");
    assertThat(PasEventType.POLICY_CREATED.legacyQueue()).isEmpty();
    assertThat(PasEventType.fromTopic("pas.underwriting.decided"))
        .contains(PasEventType.UNDERWRITING_DECIDED);
  }

  @Test
  void oversizedPayloadsAreRejected() {
    assertThatThrownBy(() -> new OutboxMessage("id", "t", "t", "k", "x".repeat(16001)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
