package com.acme.insurance.pas.policy.claims;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.claims.rest")
public record ClaimsRestProperties(
    String baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int maxAttempts,
    Duration retryBackoff) {}
