package com.acme.insurance.pas.batch.security;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * OIDC JWT resource server for pas-batch (ADR 0001 D5). The batch operator API needs scope {@code
 * batch:operate}; health probes and the Prometheus scrape stay open; everything else is denied.
 * Tokens must carry the configured audience; without {@code PAS_OIDC_ISSUER_URI} and {@code
 * PAS_OIDC_AUDIENCE} the API fails closed (every bearer JWT is rejected). Not loaded by the
 * one-shot CLI profiles ({@code spring.main.web-application-type=none}), which serve no HTTP.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class BatchSecurityConfig {

  public static final String BATCH_OPERATE = "SCOPE_batch:operate";

  private static final Logger log = LoggerFactory.getLogger(BatchSecurityConfig.class);

  @Bean
  public SecurityFilterChain batchSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/api/batch/**")
                    .hasAuthority(BATCH_OPERATE)
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // bearer-JWT API without cookies or sessions: no CSRF exposure
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/batch/**"));
    return http.build();
  }

  @Bean
  public JwtDecoder jwtDecoder(
      @Value("${pas.security.oidc.issuer-uri:}") String issuer,
      @Value("${pas.security.oidc.audience:}") String audience) {
    if (!StringUtils.hasText(issuer) || !StringUtils.hasText(audience)) {
      log.warn(
          "PAS_OIDC_ISSUER_URI / PAS_OIDC_AUDIENCE not set: the batch operator API rejects every"
              + " request");
      return jwt -> {
        throw new BadJwtException("OIDC issuer/audience not configured");
      };
    }
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
          List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
          validators.add(JwtValidators.createDefaultWithIssuer(issuer));
          validators.add(
              new JwtClaimValidator<List<String>>(
                  JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
          decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
          return decoder;
        });
  }
}
