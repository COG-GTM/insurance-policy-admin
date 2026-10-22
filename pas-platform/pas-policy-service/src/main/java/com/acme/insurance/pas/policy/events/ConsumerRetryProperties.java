package com.acme.insurance.pas.policy.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.events.consumer.retry")
public record ConsumerRetryProperties(
    int maxRetries, Duration initialInterval, double multiplier, Duration maxInterval) {}
