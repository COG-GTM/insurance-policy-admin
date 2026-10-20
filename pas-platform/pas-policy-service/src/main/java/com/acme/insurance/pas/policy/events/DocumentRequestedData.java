package com.acme.insurance.pas.policy.events;

import java.util.UUID;

/**
 * {@code DocumentRequested} payload on {@code pas.document.requests.v1}: ask the FileNet bridge to
 * produce the policy document for the envelope's {@code policyNumber}/{@code policyVersion}.
 *
 * @param documentTrigger {@code NEW_BUSINESS}, {@code RENEWAL} or {@code ENDORSEMENT}
 * @param sourceTransaction the CICS transaction whose message layout the bridge reproduces ({@code
 *     POLNEW}, {@code POLRNW}, {@code POLEND})
 * @param triggeringEventId {@code eventId} of the domain event committed in the same transaction
 * @param triggeringEventType that event's {@code eventType}
 */
public record DocumentRequestedData(
    String documentTrigger,
    String sourceTransaction,
    UUID triggeringEventId,
    String triggeringEventType) {

  public static final String NEW_BUSINESS = "NEW_BUSINESS";
  public static final String RENEWAL = "RENEWAL";
  public static final String ENDORSEMENT = "ENDORSEMENT";
}
