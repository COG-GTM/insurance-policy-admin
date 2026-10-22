package com.acme.insurance.pas.policy.platform;

import com.acme.insurance.pas.common.security.PasScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PolicySecurityConfig {
  @Bean
  SecurityFilterChain policySecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(
            csrf -> csrf.requireCsrfProtectionMatcher(PolicySecurityConfig::requiresCsrfProtection))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    .requestMatchers("/api/v1/policies/**")
                    .hasAuthority(PasScopes.authority(PasScopes.POLICY_READ))
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer.jwt(
                    jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  private static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new JwtGrantedAuthoritiesConverter());
    return jwt -> {
      String subject = jwt.getSubject();
      if (subject == null || subject.length() > 255) {
        throw new InvalidBearerTokenException("JWT subject is missing or too long");
      }
      return converter.convert(jwt);
    };
  }

  private static boolean requiresCsrfProtection(HttpServletRequest request) {
    String method = request.getMethod();
    boolean unsafe =
        !("GET".equals(method)
            || "HEAD".equals(method)
            || "TRACE".equals(method)
            || "OPTIONS".equals(method));
    String authorization = request.getHeader("Authorization");
    boolean bearerToken =
        authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    return unsafe && request.getCookies() != null && !bearerToken;
  }
}
