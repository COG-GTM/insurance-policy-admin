package com.acme.insurance.pas.persistence;

import java.time.LocalDateTime;

/** An outbox row the relay parked after repeated failures (V80, AB-528). */
public record ParkedEvent(OutboxRow row, LocalDateTime parkedAt, int replayCount) {}
