package com.acme.insurance.pas.policy.claims;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link ClaimsHistoryPort} adapter with {@code pas.claims.adapter}: {@code stub} for
 * local/test environments, anything else (default {@code unavailable}) refuses underwriting until
 * the Claims-owned adapter is added here (Pending: Claims team).
 */
@Configuration
public class ClaimsHistoryConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ClaimsHistoryConfiguration.class);

  @Bean
  @ConditionalOnProperty(name = "pas.claims.adapter", havingValue = "stub")
  InMemoryClaimsHistoryAdapter stubClaimsHistory() {
    log.warn(
        "pas.claims.adapter=stub: claims history is an in-memory stub; not for production use");
    return new InMemoryClaimsHistoryAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(ClaimsHistoryPort.class)
  ClaimsHistoryPort unavailableClaimsHistory() {
    return new UnavailableClaimsHistoryAdapter();
  }
}
