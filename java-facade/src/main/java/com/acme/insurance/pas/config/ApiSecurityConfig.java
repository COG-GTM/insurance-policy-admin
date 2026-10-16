package com.acme.insurance.pas.config;

import com.acme.insurance.pas.security.ApiTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/**
 * Wires bearer-token authentication onto the /api/** routes.
 * An interceptor (rather than a servlet filter) is used so that
 * authentication runs on the exact path Spring MVC routes on.
 *
 * @author T. Nguyen (2022)
 */
@Configuration
public class ApiSecurityConfig extends WebMvcConfigurerAdapter {

    @Value("${pas.security.api-tokens:}")
    private String apiTokens;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiTokenInterceptor(apiTokens))
                .addPathPatterns("/api/**");
    }
}
