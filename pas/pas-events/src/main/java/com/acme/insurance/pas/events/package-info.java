/**
 * Domain events that replace the CICS TD queue writes (ADR 0001 D4): the event types and their
 * topics, the version 1 payloads and the JSON envelope written to the transactional outbox. No
 * broker publish outside the outbox; the relay in the application reads the outbox. AB-525 adds
 * policy created, underwriting requested and underwriting decided; AB-528 registers the schemas,
 * the MQ bridge and dead-letter handling. May depend on pas-domain only.
 */
package com.acme.insurance.pas.events;
