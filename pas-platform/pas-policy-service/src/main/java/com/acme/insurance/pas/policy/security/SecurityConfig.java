package com.acme.insurance.pas.policy.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Every endpoint needs a valid JWT except the Kubernetes health probes. Route scopes: {@code POST
 * /api/v1/policies} needs {@code policy:write}; {@code POST
 * /api/v1/policies/{n}/underwriting-decisions[/overrides]} needs {@code underwriting:decide}; every
 * other {@code /api/v1/policies} route needs {@code policy:read}. Object-level checks run in the
 * service layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  static final String[] HEALTH_PROBES = {
    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
  };

  static final String[] UNDERWRITING_WRITES = {
    "/api/v1/policies/*/underwriting-decisions",
    "/api/v1/policies/*/underwriting-decisions/overrides"
  };

  /**
   * CSRF only matters when the browser sends ambient credentials (cookies) on an unsafe request.
   * Bearer tokens are never attached automatically, and this stateless API issues no cookies, so
   * everything else falls through to the JWT entry point (401) instead of a CSRF 403.
   */
  static final RequestMatcher CSRF_PROTECTED_REQUEST =
      new AndRequestMatcher(
          CsrfFilter.DEFAULT_CSRF_MATCHER,
          request -> request.getCookies() != null && request.getCookies().length > 0,
          request -> {
            String authorization = request.getHeader("Authorization");
            return authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7);
          });

  @Bean
  SecurityFilterChain pasSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.requireCsrfProtectionMatcher(CSRF_PROTECTED_REQUEST))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HEALTH_PROBES)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/policies")
                    .hasAuthority(PasScopes.authority(PasScopes.POLICY_WRITE))
                    .requestMatchers(HttpMethod.POST, UNDERWRITING_WRITES)
                    .hasAuthority(PasScopes.authority(PasScopes.UNDERWRITING_DECIDE))
                    .requestMatchers("/api/v1/admin/**")
                    .hasAuthority(PasScopes.authority(PasScopes.BATCH_OPERATE))
                    .requestMatchers("/api/v1/policies/**")
                    .hasAuthority(PasScopes.authority(PasScopes.POLICY_READ))
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
