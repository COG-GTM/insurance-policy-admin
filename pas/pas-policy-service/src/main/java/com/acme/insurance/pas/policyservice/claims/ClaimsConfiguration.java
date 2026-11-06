package com.acme.insurance.pas.policyservice.claims;

import com.acme.insurance.pas.underwriting.ClaimsHistory;
import com.acme.insurance.pas.underwriting.ClaimsHistoryUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Wires {@link ClaimsHistory}: HTTP with OAuth2 client credentials, or "not configured". */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClaimsProperties.class)
public class ClaimsConfiguration {

  @Bean
  ClaimsHistory claimsHistory(
      ClaimsProperties properties,
      RestClient.Builder builder,
      ObjectProvider<ClientRegistrationRepository> registrations) {
    if (!StringUtils.hasText(properties.baseUrl())) {
      return (policyNumber, from, to) -> {
        throw new ClaimsHistoryUnavailableException("pas.claims.base-url is not configured");
      };
    }
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.connectTimeout());
    factory.setReadTimeout(properties.readTimeout());
    RestClient.Builder client =
        builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory);
    if (StringUtils.hasText(properties.clientRegistrationId())) {
      ClientRegistrationRepository repository = registrations.getIfAvailable();
      if (repository == null) {
        throw new IllegalStateException(
            "pas.claims.client-registration-id is set but no OAuth2 client registration exists");
      }
      var manager =
          new AuthorizedClientServiceOAuth2AuthorizedClientManager(
              repository, new InMemoryOAuth2AuthorizedClientService(repository));
      OAuth2ClientHttpRequestInterceptor interceptor =
          new OAuth2ClientHttpRequestInterceptor(manager);
      String id = properties.clientRegistrationId();
      interceptor.setClientRegistrationIdResolver(request -> id);
      client.requestInterceptor(interceptor);
    }
    return new HttpClaimsHistory(client.build());
  }
}
