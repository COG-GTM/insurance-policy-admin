package com.acme.insurance.pas.policyservice.events;

import com.acme.insurance.pas.events.OutboxMessage;
import com.acme.insurance.pas.events.PasEvent;
import com.acme.insurance.pas.events.PasEvents;
import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.OutboxRow;
import org.springframework.stereotype.Component;

/**
 * Appends events to PASAPP.EVENT_OUTBOX in the caller's transaction (ADR 0001 D4): the event exists
 * if and only if the business change committed. {@link OutboxRelay} publishes it.
 */
@Component
public class OutboxWriter {

  private final EventOutboxJdbcRepository outbox;

  public OutboxWriter(EventOutboxJdbcRepository outbox) {
    this.outbox = outbox;
  }

  public void append(PasEvent event) {
    OutboxMessage m = PasEvents.toOutboxMessage(event);
    outbox.append(OutboxRow.pending(m.eventId(), m.eventType(), m.topic(), m.key(), m.payload()));
  }
}
