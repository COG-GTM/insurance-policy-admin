package com.acme.insurance.pas.config;

import com.acme.insurance.pas.config.ApiClientProperties.ApiClient;
import org.junit.Test;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SecurityConfigTests {

    private static final String PASSWORD_HASH =
            "$2a$10$Luz3QFRd4Qu7aUkZ0nfCGeJD4YVPsBsoNY85ZkTpNHHQZ8/Bp4Xta";

    @Test
    public void invalidPasswordHashFailsWithClientUsername() {
        String invalidHash = "not-a-bcrypt-hash";
        ApiClientProperties properties = properties(
                client("invalid-client", invalidHash));

        IllegalStateException exception = configurationException(properties);

        assertTrue(exception.getMessage().contains("invalid-client"));
        assertFalse(exception.getMessage().contains(invalidHash));
    }

    @Test
    public void duplicateUsernamesAreCaseInsensitive() {
        ApiClientProperties properties = properties(
                client("a", PASSWORD_HASH), client("A", PASSWORD_HASH));

        IllegalStateException exception = configurationException(properties);

        assertTrue(exception.getMessage().contains("A"));
        assertFalse(exception.getMessage().contains(PASSWORD_HASH));
    }

    @Test
    public void emptyClientListFailsFast() {
        ApiClientProperties properties = new ApiClientProperties();
        properties.setClients(Collections.<ApiClient>emptyList());

        IllegalStateException exception = configurationException(properties);

        assertTrue(exception.getMessage().contains("No API clients configured"));
    }

    @Test
    public void validConfigurationBuildsUserDetailsService() {
        ApiClientProperties properties = properties(
                client("valid-client", PASSWORD_HASH));

        UserDetailsService userDetailsService =
                new SecurityConfig(properties).apiClientUserDetailsService();

        assertNotNull(userDetailsService.loadUserByUsername("valid-client"));
    }

    private static ApiClientProperties properties(ApiClient... clients) {
        ApiClientProperties properties = new ApiClientProperties();
        properties.setClients(Arrays.asList(clients));
        return properties;
    }

    private static ApiClient client(String username, String passwordHash) {
        ApiClient client = new ApiClient();
        client.setUsername(username);
        client.setPasswordHash(passwordHash);
        return client;
    }

    private static IllegalStateException configurationException(
            ApiClientProperties properties) {
        try {
            new SecurityConfig(properties).apiClientUserDetailsService();
        } catch (IllegalStateException exception) {
            return exception;
        }
        throw new AssertionError("Expected invalid API client configuration");
    }
}
