package com.acme.insurance.pas.policyservice.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny by default (ADR 0001 D5). Only the probe, info and metrics endpoints are open; they are
 * served on the separate management port, which is not routed through ingress. Every other request
 * is rejected until AB-524 adds the OAuth 2.0 resource server and per-capability scopes.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  SecurityFilterChain pasSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        EndpointRequest.to(
                            HealthEndpoint.class,
                            InfoEndpoint.class,
                            PrometheusScrapeEndpoint.class))
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .headers(Customizer.withDefaults());
    return http.build();
  }
}
