/**
 * Domain events via the transactional outbox (ADR 0001 D4, AB-525): {@link
 * com.acme.insurance.pas.policy.events.OutboxWriter} appends in the business transaction, {@link
 * com.acme.insurance.pas.policy.events.OutboxRelay} publishes to Kafka. AB-526/AB-528 add further
 * event types through the same writer.
 */
package com.acme.insurance.pas.policy.events;
