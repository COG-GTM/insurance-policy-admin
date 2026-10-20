package com.acme.insurance.pas.policy.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pas.integration.*}: legacy queue bridges (AB-528). The bridges are off unless {@code
 * bridges-enabled=true}; real MQ/FileNet endpoints are Pending: Document/FileNet team, so the only
 * transports are {@code logging} (default) and {@code memory} (test environments).
 */
@ConfigurationProperties(prefix = "pas.integration")
public record IntegrationProperties(
    boolean bridgesEnabled,
    String transport,
    String filenetQueue,
    String underwritingRequestQueue,
    String underwritingDecisionQueue,
    int maxRetries,
    Duration retryInitialInterval,
    int retryMultiplier,
    Duration retryMaxInterval,
    int memoryCapacity) {

  public static final String TRANSPORT_LOGGING = "logging";
  public static final String TRANSPORT_MEMORY = "memory";
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final int DEFAULT_MULTIPLIER = 2;
  private static final int DEFAULT_MEMORY_CAPACITY = 500;

  public IntegrationProperties {
    transport = transport == null || transport.isBlank() ? TRANSPORT_LOGGING : transport.strip();
    if (filenetQueue == null || filenetQueue.isBlank()) {
      filenetQueue = "PENDING.FILENET.DOCUMENT.TRIGGER";
    }
    if (underwritingRequestQueue == null || underwritingRequestQueue.isBlank()) {
      underwritingRequestQueue = "ACME.PAS.UNDERWRITING.REQUEST";
    }
    if (underwritingDecisionQueue == null || underwritingDecisionQueue.isBlank()) {
      underwritingDecisionQueue = "ACME.PAS.UNDERWRITING.DECISION";
    }
    if (maxRetries < 0) {
      maxRetries = DEFAULT_MAX_RETRIES;
    }
    if (retryInitialInterval == null) {
      retryInitialInterval = Duration.ofSeconds(1);
    }
    if (retryMultiplier < 1) {
      retryMultiplier = DEFAULT_MULTIPLIER;
    }
    if (retryMaxInterval == null) {
      retryMaxInterval = Duration.ofSeconds(30);
    }
    if (memoryCapacity < 1) {
      memoryCapacity = DEFAULT_MEMORY_CAPACITY;
    }
  }
}
