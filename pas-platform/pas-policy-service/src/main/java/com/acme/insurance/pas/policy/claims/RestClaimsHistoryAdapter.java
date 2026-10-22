package com.acme.insurance.pas.policy.claims;

import com.acme.insurance.pas.domain.claims.ClaimsHistoryPort;
import com.acme.insurance.pas.domain.claims.ClaimsSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

public class RestClaimsHistoryAdapter implements ClaimsHistoryPort {
  private final RestClient restClient;
  private final ClaimsRestProperties properties;

  public RestClaimsHistoryAdapter(
      ClaimsRestProperties properties, OAuth2AuthorizedClientManager authorizedClientManager) {
    this.properties = properties;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    OAuth2ClientHttpRequestInterceptor oauth2Interceptor =
        new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
    oauth2Interceptor.setClientRegistrationIdResolver(request -> "claims-api");
    this.restClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .requestInterceptor(oauth2Interceptor)
            .build();
  }

  @Override
  public int countOpenClaims(String policyNumber) {
    try {
      return retry(
          () ->
              restClient
                  .get()
                  .uri("/claims/v1/policies/{policyNumber}/open-claims", policyNumber)
                  .retrieve()
                  .body(OpenClaimsResponse.class)
                  .openClaimCount());
    } catch (HttpClientErrorException.NotFound exception) {
      return 0;
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  @Override
  public ClaimsSummary summarizeSince(String policyNumber, LocalDate since) {
    try {
      SummaryResponse response =
          retry(
              () ->
                  restClient
                      .get()
                      .uri(
                          builder ->
                              builder
                                  .path("/claims/v1/policies/{policyNumber}/summary")
                                  .queryParam("since", since)
                                  .build(policyNumber))
                      .retrieve()
                      .body(SummaryResponse.class));
      return new ClaimsSummary(
          response.claimCount(),
          response.totalIncurred() == null
              ? BigDecimal.ZERO.setScale(2)
              : response.totalIncurred());
    } catch (HttpClientErrorException.NotFound exception) {
      return new ClaimsSummary(0, BigDecimal.ZERO.setScale(2));
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private <T> T retry(Supplier<T> request) {
    int attempts = Math.max(1, properties.maxAttempts());
    for (int attempt = 1; ; attempt++) {
      try {
        return request.get();
      } catch (HttpServerErrorException | ResourceAccessException exception) {
        if (attempt >= attempts) {
          throw exception;
        }
        try {
          Thread.sleep(properties.retryBackoff().toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ClaimsHistoryUnavailableException(
              "Claims request was interrupted", interrupted);
        }
      }
    }
  }

  private static ClaimsHistoryUnavailableException unavailable(RuntimeException cause) {
    return new ClaimsHistoryUnavailableException("Claims history service is unavailable", cause);
  }

  private record OpenClaimsResponse(int openClaimCount) {}

  private record SummaryResponse(int claimCount, BigDecimal totalIncurred) {}
}
