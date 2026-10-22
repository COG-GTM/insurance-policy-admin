package com.acme.insurance.pas.policy.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.outbox.relay")
public record OutboxRelayProperties(
    boolean enabled, Duration pollInterval, int batchSize, Duration ackTimeout) {}
