package com.acme.insurance.pas.policyservice.config;

import static com.acme.insurance.pas.policyservice.security.PasScopes.OPS_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICY_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.authority;

import com.acme.insurance.pas.policyservice.security.PasSecurityProperties;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * ADR 0001 D5: every request is authenticated with an OAuth 2.0 / OIDC access token (issuer,
 * signature, audience and expiry validated) and denied unless a rule below allows it. Health probes
 * stay open on the management port for the platform; info and metrics need {@code pas.ops.read};
 * every other actuator endpoint is denied.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasSecurityProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain pasSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    .requestMatchers(
                        EndpointRequest.to(InfoEndpoint.class, PrometheusScrapeEndpoint.class))
                    .hasAuthority(authority(OPS_READ))
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .denyAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/policies/*", "/api/v1/policies/*/*")
                    .hasAuthority(authority(POLICY_READ))
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .headers(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(PasSecurityProperties properties, ResourceLoader resources)
      throws IOException {
    if (!StringUtils.hasText(properties.issuer()) || !StringUtils.hasText(properties.audience())) {
      throw new IllegalStateException("pas.security.jwt.issuer and audience must be configured");
    }
    NimbusJwtDecoder decoder;
    if (StringUtils.hasText(properties.jwkSetUri())) {
      decoder =
          NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
              .jwsAlgorithm(SignatureAlgorithm.RS256)
              .build();
    } else if (StringUtils.hasText(properties.publicKeyLocation())) {
      RSAPublicKey key;
      try (InputStream in =
          resources.getResource(properties.publicKeyLocation()).getInputStream()) {
        key = RsaKeyConverters.x509().convert(in);
      }
      decoder =
          NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    } else {
      throw new IllegalStateException(
          "pas.security.jwt.jwk-set-uri or public-key-location must be configured");
    }
    decoder.setJwtValidator(validator(properties));
    return decoder;
  }

  static OAuth2TokenValidator<Jwt> validator(PasSecurityProperties properties) {
    String audience = properties.audience();
    return new DelegatingOAuth2TokenValidator<>(
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        new JwtTimestampValidator(properties.clockSkew()),
        new JwtIssuerValidator(properties.issuer()),
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
  }
}
