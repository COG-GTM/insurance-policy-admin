package com.acme.insurance.pas.policy.integration;

import java.time.LocalDateTime;

/**
 * A parked dead letter ({@code pas.dead_letters}). {@code payload} is the original record value.
 */
public record DeadLetterView(
    long deadLetterId,
    String dltTopic,
    String originalTopic,
    String consumerGroup,
    String messageKey,
    String eventId,
    String eventType,
    String errorClass,
    String errorMessage,
    String status,
    LocalDateTime recordedAt,
    int replayCount,
    LocalDateTime replayedAt,
    String replayedBy,
    String payload) {}
