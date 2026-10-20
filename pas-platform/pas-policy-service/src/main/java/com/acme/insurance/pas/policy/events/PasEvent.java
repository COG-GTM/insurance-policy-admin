package com.acme.insurance.pas.policy.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Event envelope from ADR 0001 D4. {@code data} carries identifiers and business facts only, never
 * policyholder PII. Consumers dedupe on {@code eventId} (delivery is at-least-once).
 */
public record PasEvent(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String policyNumber,
    long policyVersion,
    String correlationId,
    Object data) {}
