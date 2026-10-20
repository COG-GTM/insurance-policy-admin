package com.acme.insurance.pas.config;

import com.acme.insurance.pas.security.ApiKeyAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
public class ApiSecurityConfig extends WebMvcConfigurerAdapter {

    private final ApiKeyAuthenticationInterceptor apiKeyAuthenticationInterceptor;

    public ApiSecurityConfig(ApiKeyAuthenticationInterceptor apiKeyAuthenticationInterceptor) {
        this.apiKeyAuthenticationInterceptor = apiKeyAuthenticationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyAuthenticationInterceptor)
                .addPathPatterns("/api/**");
    }
}
