package com.acme.insurance.pas.policy.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pas.outbox.relay.*}: outbox to Kafka relay tuning. */
@ConfigurationProperties("pas.outbox.relay")
public record OutboxRelayProperties(
    boolean enabled, Duration pollInterval, int batchSize, Duration sendTimeout) {

  private static final int DEFAULT_BATCH_SIZE = 100;
  private static final int DEFAULT_SEND_TIMEOUT_SECONDS = 15;
  private static final int DEFAULT_POLL_SECONDS = 1;

  public OutboxRelayProperties {
    if (pollInterval == null) {
      pollInterval = Duration.ofSeconds(DEFAULT_POLL_SECONDS);
    }
    if (batchSize <= 0) {
      batchSize = DEFAULT_BATCH_SIZE;
    }
    if (sendTimeout == null) {
      sendTimeout = Duration.ofSeconds(DEFAULT_SEND_TIMEOUT_SECONDS);
    }
  }
}
