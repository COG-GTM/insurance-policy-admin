package com.acme.insurance.pas.policy.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default transport while the MQ/FileNet endpoints are pending: logs metadata, never PII. */
public class LoggingLegacyQueueTransport implements LegacyQueueTransport {

  private static final Logger log = LoggerFactory.getLogger(LoggingLegacyQueueTransport.class);

  @Override
  public void send(LegacyQueueMessage m) {
    log.info(
        "legacy queue message not delivered (transport pending): queue={} consumer={} eventId={}"
            + " policy={} version={} source={} bytes={}",
        m.queue(),
        m.consumer(),
        m.eventId(),
        m.policyNumber(),
        m.policyVersion(),
        m.sourceTransaction(),
        m.length());
  }
}
