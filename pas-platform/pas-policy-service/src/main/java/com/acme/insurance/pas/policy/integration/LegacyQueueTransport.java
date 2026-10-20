package com.acme.insurance.pas.policy.integration;

/**
 * Delivers a fixed-width message to a legacy queue consumer (FileNet document trigger, underwriting
 * referral queues). The real transport (IBM MQ via JMS, or FileNet's own API) is Pending:
 * Document/FileNet team and Pending: Underwriting team; until then {@link
 * LoggingLegacyQueueTransport} or {@link InMemoryLegacyQueueTransport} is used. Implementations
 * throw to make the bridge retry and, after the retries, dead-letter the event.
 */
public interface LegacyQueueTransport {

  void send(LegacyQueueMessage message);
}
