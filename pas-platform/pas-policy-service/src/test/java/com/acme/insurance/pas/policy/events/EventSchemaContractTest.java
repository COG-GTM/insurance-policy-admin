package com.acme.insurance.pas.policy.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.policy.lifecycle.PolicyCreatedData;
import com.acme.insurance.pas.policy.underwriting.UnderwritingDecisionData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract test: the envelope {@link OutboxWriter} serialises for every event type validates
 * against the committed v1 JSON Schema (same Jackson settings as the service: ISO dates, numbers).
 * PolicyRenewed/PolicyEndorsed are emitted by AB-526 through {@link PolicyEventPublisher}; this
 * pins their contract before that lands.
 */
class EventSchemaContractTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final LocalDate EFF = LocalDate.of(2025, 6, 1);

  @Test
  void policyCreated() {
    assertValid(
        PasEventTypes.POLICY_CREATED,
        new PolicyCreatedData(
            "AUT",
            "PN",
            "PN",
            "CUST000001",
            "AG0001",
            "B001",
            EFF,
            EFF.plusYears(1),
            EFF,
            BigDecimal.valueOf(25000000, 2),
            BigDecimal.valueOf(50000, 2),
            BigDecimal.valueOf(120000, 2),
            List.of(new PolicyCreatedData.CoverageSummary(1, "BI"))));
  }

  @Test
  void policyRenewed() {
    assertValid(
        PasEventTypes.POLICY_RENEWED,
        new PolicyRenewedData(
            "AUT",
            "AC",
            "PN",
            EFF,
            EFF,
            EFF.plusYears(1),
            1,
            BigDecimal.valueOf(100000, 2),
            BigDecimal.valueOf(115000, 2),
            BigDecimal.valueOf(15)));
  }

  @Test
  void policyEndorsed() {
    assertValid(
        PasEventTypes.POLICY_ENDORSED,
        new PolicyEndorsedData(
            1,
            null,
            null,
            BigDecimal.ZERO,
            BigDecimal.valueOf(100000, 2),
            "AC",
            EFF,
            EFF.plusYears(1)));
  }

  @Test
  void underwritingDecidedAndReferred() {
    UnderwritingDecisionData data =
        new UnderwritingDecisionData(7L, EFF, "AP", 200, 200, "AUTO", "SYSTEM", false, null);
    assertValid(PasEventTypes.UNDERWRITING_DECIDED, data);
    assertValid(PasEventTypes.UNDERWRITING_REFERRED, data);
  }

  @Test
  void documentRequested() {
    assertValid(
        PasEventTypes.DOCUMENT_REQUESTED,
        new DocumentRequestedData(
            DocumentRequestedData.RENEWAL,
            PolicyEventPublisher.POLRNW,
            UUID.randomUUID(),
            PasEventTypes.POLICY_RENEWED));
  }

  @Test
  void rejectsAPayloadThatBreaksTheContract() throws Exception {
    JsonNode bad =
        MAPPER.readTree(
            MAPPER.writeValueAsString(
                envelope(
                    PasEventTypes.DOCUMENT_REQUESTED,
                    new DocumentRequestedData("REPRINT", "POLQRY", UUID.randomUUID(), "x"))));
    assertThat(schema(PasEventTypes.DOCUMENT_REQUESTED).validate(bad)).isNotEmpty();
  }

  private static void assertValid(String eventType, Object data) {
    try {
      JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(envelope(eventType, data)));
      Set<ValidationMessage> errors = schema(eventType).validate(json);
      assertThat(errors).as(eventType + " payload " + json).isEmpty();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PasEvent envelope(String eventType, Object data) {
    return new PasEvent(
        UUID.randomUUID(),
        eventType,
        PasEventTypes.SCHEMA_VERSION,
        Instant.parse("2026-01-01T00:00:00Z"),
        "POL000000001",
        3L,
        "corr-1",
        data);
  }

  private static JsonSchema schema(String eventType) {
    String resource = "/events/schemas/v1/" + eventType + ".schema.json";
    try (InputStream in = EventSchemaContractTest.class.getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
