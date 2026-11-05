package com.acme.insurance.pas.policyservice.claims;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.claims.*}: the Claims-team history interface (INT-03, FU-17). Without a base URL the
 * service starts, but underwriting answers 503: no decision is taken without the loss history.
 *
 * @param baseUrl base URL of the Claims history API
 * @param clientRegistrationId Spring Security OAuth2 client registration used for client
 *     credentials; blank sends no token (local stacks and tests only)
 * @param connectTimeout TCP connect timeout
 * @param readTimeout response timeout
 */
@ConfigurationProperties("pas.claims")
public record ClaimsProperties(
    String baseUrl,
    String clientRegistrationId,
    @DefaultValue("PT2S") Duration connectTimeout,
    @DefaultValue("PT5S") Duration readTimeout) {}
