package com.acme.insurance.pas.security;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class ApiKeyAuthenticationInterceptorTests {

    private static final String FIRST_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SECOND_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void validateClients_duplicateHashesThrowsWithClientIdsOnly() {
        ApiKeyAuthenticationInterceptor interceptor =
                new ApiKeyAuthenticationInterceptor(properties(FIRST_HASH, FIRST_HASH));

        try {
            interceptor.validateClients();
            fail("Expected duplicate API key hashes to be rejected");
        } catch (IllegalStateException e) {
            assertEquals(
                    "Duplicate API key hash configured for clients: client-1, client-2",
                    e.getMessage());
            assertFalse(e.getMessage().contains(FIRST_HASH));
        }
    }

    @Test
    public void validateClients_distinctHashesDoesNotThrow() {
        ApiKeyAuthenticationInterceptor interceptor =
                new ApiKeyAuthenticationInterceptor(properties(FIRST_HASH, SECOND_HASH));

        interceptor.validateClients();
    }

    private static ApiClientProperties properties(String... hashes) {
        ApiClientProperties properties = new ApiClientProperties();
        List<ApiClientProperties.Client> clients =
                new ArrayList<ApiClientProperties.Client>();
        for (int i = 0; i < hashes.length; i++) {
            ApiClientProperties.Client client = new ApiClientProperties.Client();
            client.setId("client-" + (i + 1));
            client.setKeySha256(hashes[i]);
            clients.add(client);
        }
        properties.setClients(clients);
        return properties;
    }
}
