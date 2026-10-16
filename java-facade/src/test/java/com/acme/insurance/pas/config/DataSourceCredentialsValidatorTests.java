package com.acme.insurance.pas.config;

import org.junit.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class DataSourceCredentialsValidatorTests {

    private final DataSourceCredentialsValidator validator = new DataSourceCredentialsValidator();

    @Test
    public void rejectsUnresolvedDatabaseCredentials() {
        MockEnvironment environment = db2Environment();
        environment.setProperty("spring.datasource.username", "${DB2_USERNAME}");
        environment.setProperty("spring.datasource.password", "${DB2_PASSWORD}");

        assertCredentialsRejected(environment);
    }

    @Test
    public void rejectsMissingPassword() {
        MockEnvironment environment = db2Environment();
        environment.setProperty("spring.datasource.username", "test-user");

        assertCredentialsRejected(environment);
    }

    @Test
    public void rejectsEmptyPassword() {
        MockEnvironment environment = db2Environment();
        environment.setProperty("spring.datasource.username", "test-user");
        environment.setProperty("spring.datasource.password", "");

        assertCredentialsRejected(environment);
    }

    @Test
    public void acceptsConfiguredDatabaseCredentials() {
        MockEnvironment environment = db2Environment();
        environment.setProperty("spring.datasource.username", "test-user");
        environment.setProperty("spring.datasource.password", "test-secret");

        validator.postProcessEnvironment(environment, null);
    }

    @Test
    public void ignoresH2DatasourceWithoutCredentials() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:x");

        validator.postProcessEnvironment(environment, null);
    }

    @Test
    public void applicationPropertiesRequireRuntimeCredentials() throws IOException {
        MockEnvironment environment = applicationPropertiesEnvironment();

        assertCredentialsRejected(environment);
    }

    @Test
    public void applicationPropertiesResolveRuntimeCredentials() throws IOException {
        MockEnvironment environment = applicationPropertiesEnvironment();
        environment.setProperty("DB2_USERNAME", "test-user");
        environment.setProperty("DB2_PASSWORD", "test-secret");

        validator.postProcessEnvironment(environment, null);

        assertEquals("test-secret", environment.getProperty("spring.datasource.password"));
    }

    @Test
    public void dockerfileDoesNotBakeInDatabaseCredentials() throws IOException {
        String dockerfile = new String(Files.readAllBytes(Paths.get("Dockerfile")), "UTF-8");

        assertFalse(Pattern.compile("(?m)^\\s*ENV\\s+DB2_(USERNAME|PASSWORD)\\b")
                .matcher(dockerfile).find());
    }

    private MockEnvironment db2Environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:db2://example");
        return environment;
    }

    private MockEnvironment applicationPropertiesEnvironment() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get("src/main/resources/application.properties"))) {
            properties.load(input);
        }
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("application", properties));
        return environment;
    }

    private void assertCredentialsRejected(MockEnvironment environment) {
        try {
            validator.postProcessEnvironment(environment, null);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("DB2 datasource credentials are not configured: set DB2_USERNAME and DB2_PASSWORD (injected from the secrets manager). No default credentials are permitted.",
                    expected.getMessage());
        }
    }
}
