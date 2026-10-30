package com.acme.insurance.pas.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

/**
 * Requires an authenticated API client on every {@code /api/**} request.
 * Other paths (actuator, Swagger) keep their existing configuration.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiClientProperties.class)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final ApiClientRegistry registry;

    @Autowired
    public SecurityConfig(ApiClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
            .requestCache().disable()
            .logout().disable()
            .headers().frameOptions().sameOrigin().and()
            .exceptionHandling()
                .authenticationEntryPoint(unauthorized())
                .accessDeniedHandler(forbidden()).and()
            .addFilterBefore(new ApiKeyAuthenticationFilter(registry),
                    AnonymousAuthenticationFilter.class)
            .authorizeRequests()
                .antMatchers("/api/**").hasRole(ApiKeyAuthenticationFilter.ROLE)
                .anyRequest().permitAll();
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> {
            AuditLog.record(null, request.getMethod(), request.getRequestURI(),
                    HttpServletResponse.SC_UNAUTHORIZED, request.getRemoteAddr());
            response.setHeader("WWW-Authenticate", "ApiKey header=\""
                    + ApiKeyAuthenticationFilter.HEADER + "\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key required");
        };
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, ex) -> {
            AuditLog.record(request.getRemoteUser(), request.getMethod(),
                    request.getRequestURI(), HttpServletResponse.SC_FORBIDDEN,
                    request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        };
    }
}
