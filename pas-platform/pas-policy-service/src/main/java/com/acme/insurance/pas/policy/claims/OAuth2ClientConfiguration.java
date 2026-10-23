package com.acme.insurance.pas.policy.claims;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
public class OAuth2ClientConfiguration {
  @Bean
  OAuth2AuthorizedClientManager claimsOAuth2AuthorizedClientManager(
      ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
    AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
        new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    manager.setAuthorizedClientProvider(
        OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
  }
}
