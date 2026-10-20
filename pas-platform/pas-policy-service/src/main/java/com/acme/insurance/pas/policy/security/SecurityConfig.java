package com.acme.insurance.pas.policy.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Every endpoint needs a valid JWT except the Kubernetes health probes. {@code /api/v1/policies}
 * also needs {@code policy:read}; object-level checks run in the service layer.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  static final String[] HEALTH_PROBES = {
    "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
  };

  /**
   * Browsers never attach an {@code Authorization} header on their own, so bearer-token requests
   * cannot be forged cross-site. CSRF stays on for anything else.
   */
  static final RequestMatcher BEARER_TOKEN_REQUEST =
      request -> {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
      };

  @Bean
  SecurityFilterChain pasSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.ignoringRequestMatchers(BEARER_TOKEN_REQUEST))
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
                    .requestMatchers("/api/v1/policies/**")
                    .hasAuthority(PasScopes.authority(PasScopes.POLICY_READ))
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
