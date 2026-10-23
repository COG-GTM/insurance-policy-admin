package com.acme.insurance.pas.batch.platform;

import com.acme.insurance.pas.common.security.PasScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class BatchSecurityConfig {
  @Bean
  SecurityFilterChain batchSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(
            csrf -> csrf.requireCsrfProtectionMatcher(BatchSecurityConfig::requiresCsrfProtection))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    .requestMatchers("/api/batch/migration/**")
                    .hasAuthority(PasScopes.authority(PasScopes.BATCH_OPERATE))
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
    return http.build();
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
