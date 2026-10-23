package com.acme.insurance.pas.batch.extract.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pas.batch.extract.retry")
public record ExtractRetryProperties(
    int maxAttempts, Duration initialBackoff, int multiplier, Duration maxBackoff) {
  public ExtractRetryProperties {
    maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
    initialBackoff = initialBackoff == null ? Duration.ofSeconds(5) : initialBackoff;
    multiplier = multiplier < 1 ? 2 : multiplier;
    maxBackoff = maxBackoff == null ? Duration.ofSeconds(60) : maxBackoff;
  }
}
