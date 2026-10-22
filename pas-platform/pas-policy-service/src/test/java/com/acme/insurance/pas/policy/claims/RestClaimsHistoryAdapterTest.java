package com.acme.insurance.pas.policy.claims;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;

class RestClaimsHistoryAdapterTest {
  private WireMockServer server;
  private RestClaimsHistoryAdapter adapter;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(0);
    server.start();
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("claims-api")
            .clientId("test-client")
            .clientSecret("test-secret")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://localhost:" + server.port() + "/oauth/token")
            .build();
    OAuth2AuthorizedClientManager manager =
        request -> {
          OAuth2AccessToken token =
              new OAuth2AccessToken(
                  TokenType.BEARER, "test-token", Instant.now(), Instant.now().plusSeconds(300));
          return new OAuth2AuthorizedClient(registration, request.getPrincipal().getName(), token);
        };
    adapter =
        new RestClaimsHistoryAdapter(
            new ClaimsRestProperties(
                "http://localhost:" + server.port(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                2,
                Duration.ZERO),
            manager);
  }

  @AfterEach
  void stopServer() {
    server.stop();
  }

  @Test
  void mapsSummaryAndTreatsNotFoundAsZero() {
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000001/summary"))
            .withQueryParam("since", equalTo("2020-01-01"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"claimCount\":2,\"totalIncurred\":123.45}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000002/open-claims"))
            .willReturn(aResponse().withStatus(404)));

    var summary = adapter.summarizeSince("POL000000001", LocalDate.of(2020, 1, 1));

    assertThat(summary.claimCount()).isEqualTo(2);
    assertThat(summary.totalIncurred()).isEqualByComparingTo(new BigDecimal("123.45"));
    assertThat(adapter.countOpenClaims("POL000000002")).isZero();
  }

  @Test
  void refusesRestModeWithoutAClaimsBaseUrl() {
    assertThatThrownBy(
            () ->
                new ClaimsConfiguration()
                    .restClaimsHistoryPort(
                        new ClaimsRestProperties(
                            " ", Duration.ofSeconds(1), Duration.ofSeconds(1), 2, Duration.ZERO),
                        request -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pas.claims.rest.base-url is required");
  }

  @Test
  void retriesServerFailuresButDoesNotRetryOtherClientFailures() {
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000001/open-claims"))
            .inScenario("retry")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recovered"));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000001/open-claims"))
            .inScenario("retry")
            .whenScenarioStateIs("recovered")
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"openClaimCount\":3}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000003/open-claims"))
            .willReturn(aResponse().withStatus(400)));

    assertThat(adapter.countOpenClaims("POL000000001")).isEqualTo(3);
    assertThatThrownBy(() -> adapter.countOpenClaims("POL000000003"))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
    server.verify(
        2, getRequestedFor(urlPathEqualTo("/claims/v1/policies/POL000000001/open-claims")));
    server.verify(
        1, getRequestedFor(urlPathEqualTo("/claims/v1/policies/POL000000003/open-claims")));
  }

  @Test
  void rejectsIncompleteOrNegativeSuccessfulResponses() {
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000004/open-claims"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000005/open-claims"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"openClaimCount\":-1}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000006/summary"))
            .withQueryParam("since", equalTo("2020-01-01"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000007/summary"))
            .withQueryParam("since", equalTo("2020-01-01"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"claimCount\":-1,\"totalIncurred\":0}")));
    server.stubFor(
        get(urlPathEqualTo("/claims/v1/policies/POL000000008/summary"))
            .withQueryParam("since", equalTo("2020-01-01"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"claimCount\":1}")));

    assertThatThrownBy(() -> adapter.countOpenClaims("POL000000004"))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
    assertThatThrownBy(() -> adapter.countOpenClaims("POL000000005"))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
    assertThatThrownBy(() -> adapter.summarizeSince("POL000000006", LocalDate.of(2020, 1, 1)))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
    assertThatThrownBy(() -> adapter.summarizeSince("POL000000007", LocalDate.of(2020, 1, 1)))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
    assertThatThrownBy(() -> adapter.summarizeSince("POL000000008", LocalDate.of(2020, 1, 1)))
        .isInstanceOf(ClaimsHistoryUnavailableException.class);
  }
}
