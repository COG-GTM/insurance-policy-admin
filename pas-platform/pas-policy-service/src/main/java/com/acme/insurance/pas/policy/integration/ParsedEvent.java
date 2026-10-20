package com.acme.insurance.pas.policy.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** The ADR 0001 envelope of a consumed event; {@code data} stays as JSON. */
public record ParsedEvent(
    UUID eventId,
    String eventType,
    int schemaVersion,
    String policyNumber,
    long policyVersion,
    String correlationId,
    JsonNode data) {

  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  /** Parses a record value; anything that is not a v1 envelope is a poison message. */
  public static ParsedEvent parse(ObjectMapper mapper, String payload) {
    if (payload == null || payload.isBlank()) {
      throw new PoisonMessageException("empty record value");
    }
    JsonNode n;
    try {
      n = mapper.readTree(payload);
    } catch (Exception e) {
      throw new PoisonMessageException("record value is not JSON", e);
    }
    try {
      int version = required(n, "schemaVersion").asInt();
      if (version != SUPPORTED_SCHEMA_VERSION) {
        throw new PoisonMessageException("unsupported schemaVersion " + version);
      }
      JsonNode policyVersion = required(n, "policyVersion");
      if (!policyVersion.canConvertToLong()) {
        throw new PoisonMessageException("policyVersion is not an integer");
      }
      JsonNode correlation = n.get("correlationId");
      return new ParsedEvent(
          UUID.fromString(required(n, "eventId").asText()),
          required(n, "eventType").asText(),
          version,
          required(n, "policyNumber").asText(),
          policyVersion.asLong(),
          correlation == null || correlation.isNull() ? null : correlation.asText(),
          n.path("data"));
    } catch (IllegalArgumentException e) {
      throw new PoisonMessageException("invalid envelope: " + e.getMessage(), e);
    }
  }

  private static JsonNode required(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      throw new PoisonMessageException("envelope has no " + field);
    }
    return v;
  }
}
