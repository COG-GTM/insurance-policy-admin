package com.acme.insurance.pas.security;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuditLogTests {

    @Test
    public void cleanStripsLogInjectionCharacters() {
        assertEquals("-", AuditLog.clean(null));
        assertEquals("/api/v1/policies/POL_x_forged_client_admin",
                AuditLog.clean("/api/v1/policies/POL\nx forged client=admin"));
    }
}
