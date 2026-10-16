package com.acme.insurance.pas.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Validates DB2 datasource credentials before datasource initialization.
 */
public class DataSourceCredentialsValidator implements EnvironmentPostProcessor {

    private static final String ERROR_MESSAGE = "DB2 datasource credentials are not configured: set DB2_USERNAME and DB2_PASSWORD (injected from the secrets manager). No default credentials are permitted.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String url = env.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:db2:")) {
            return;
        }

        String[] credentialKeys = {"spring.datasource.username", "spring.datasource.password"};
        for (String key : credentialKeys) {
            String value;
            try {
                value = env.getProperty(key);
            } catch (IllegalArgumentException ex) {
                value = null;
            }
            if (!StringUtils.hasText(value) || value.contains("${")) {
                throw new IllegalStateException(ERROR_MESSAGE);
            }
        }
    }
}
