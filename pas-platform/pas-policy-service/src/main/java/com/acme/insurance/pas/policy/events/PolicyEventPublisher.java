package com.acme.insurance.pas.policy.events;

import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way to publish a policy domain event (AB-528). Inside the business transaction it
 * snapshots the committed policy row at its {@code policyVersion}, appends the domain event to the
 * AB-525 outbox and, for new business, renewals and endorsements, appends a {@code
 * DocumentRequested} event for the FileNet bridge. Call it after {@code saveAndFlush} so the
 * policy's {@code version} is the committed one.
 */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class PolicyEventPublisher {

  public static final String POLNEW = "POLNEW";
  public static final String POLRNW = "POLRNW";
  public static final String POLEND = "POLEND";

  private final OutboxWriter outbox;
  private final PolicyVersionSnapshots snapshots;
  private final PasEventProperties topics;

  public PolicyEventPublisher(
      OutboxWriter outbox, PolicyVersionSnapshots snapshots, PasEventProperties topics) {
    this.outbox = outbox;
    this.snapshots = snapshots;
    this.topics = topics;
  }

  /** {@code PolicyCreated} plus a {@code NEW_BUSINESS} document request (POLNEW layout). */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasEvent policyCreated(PolicyEntity policy, Object data, String correlationId) {
    return withDocument(
        policyEvent(policy, PasEventTypes.POLICY_CREATED, data, correlationId),
        DocumentRequestedData.NEW_BUSINESS,
        POLNEW);
  }

  /** {@code PolicyRenewed} plus a {@code RENEWAL} document request (POLRNW layout). */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasEvent policyRenewed(PolicyEntity policy, Object data, String correlationId) {
    return withDocument(
        policyEvent(policy, PasEventTypes.POLICY_RENEWED, data, correlationId),
        DocumentRequestedData.RENEWAL,
        POLRNW);
  }

  /** {@code PolicyEndorsed} plus an {@code ENDORSEMENT} document request (POLEND layout). */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasEvent policyEndorsed(PolicyEntity policy, Object data, String correlationId) {
    return withDocument(
        policyEvent(policy, PasEventTypes.POLICY_ENDORSED, data, correlationId),
        DocumentRequestedData.ENDORSEMENT,
        POLEND);
  }

  /** {@code UnderwritingDecided} or {@code UnderwritingReferred}; no document request. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PasEvent underwritingDecision(
      PolicyEntity policy, String eventType, Object data, String correlationId) {
    snapshots.capture(policy);
    return outbox.append(
        topics.underwritingTopic(),
        eventType,
        policy.getPolicyNumber().strip(),
        policy.getVersion(),
        correlationId,
        data);
  }

  private PasEvent policyEvent(
      PolicyEntity policy, String eventType, Object data, String correlationId) {
    snapshots.capture(policy);
    return outbox.append(
        topics.policyTopic(),
        eventType,
        policy.getPolicyNumber().strip(),
        policy.getVersion(),
        correlationId,
        data);
  }

  private PasEvent withDocument(PasEvent domainEvent, String trigger, String transaction) {
    outbox.append(
        topics.documentTopic(),
        PasEventTypes.DOCUMENT_REQUESTED,
        domainEvent.policyNumber(),
        domainEvent.policyVersion(),
        domainEvent.correlationId(),
        new DocumentRequestedData(
            trigger, transaction, domainEvent.eventId(), domainEvent.eventType()));
    return domainEvent;
  }
}
