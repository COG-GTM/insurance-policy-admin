package com.acme.insurance.pas.policy.claims;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClaimsRestProperties.class, ClaimsFixtureProperties.class})
public class ClaimsConfiguration {
  @Bean
  @ConditionalOnProperty(name = "pas.claims.adapter", havingValue = "fixture")
  ClaimsHistoryPort fixtureClaimsHistoryPort(ClaimsFixtureProperties properties) {
    return new FixtureClaimsHistoryAdapter(properties.policies());
  }

  @Bean
  @ConditionalOnProperty(name = "pas.claims.adapter", havingValue = "rest", matchIfMissing = true)
  ClaimsHistoryPort restClaimsHistoryPort(
      ClaimsRestProperties properties, OAuth2AuthorizedClientManager authorizedClientManager) {
    if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "pas.claims.rest.base-url is required when pas.claims.adapter=rest");
    }
    return new RestClaimsHistoryAdapter(properties, authorizedClientManager);
  }
}
