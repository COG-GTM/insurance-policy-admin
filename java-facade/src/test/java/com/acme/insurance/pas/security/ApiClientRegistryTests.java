package com.acme.insurance.pas.security;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ApiClientRegistryTests {

    private static final String CHI_HASH =
            "01b701bdd8aaeee09f7510b2d84a318525e1a25560188a3ce6f4933df0b3935b";

    private static ApiClientProperties.Client client(String id, String hash, String... branches) {
        ApiClientProperties.Client c = new ApiClientProperties.Client();
        c.setId(id);
        c.setKeySha256(hash);
        c.setBranches(Arrays.asList(branches));
        return c;
    }

    private static ApiClientRegistry registry(ApiClientProperties.Client... clients) {
        ApiClientProperties props = new ApiClientProperties();
        props.setClients(Arrays.asList(clients));
        return new ApiClientRegistry(props);
    }

    @Test
    public void authenticatesConfiguredKeyAndScopesBranches() {
        ApiClient c = registry(client("chi", CHI_HASH.toUpperCase(), "chi1"))
                .authenticate("chi-test-key-0001");
        assertEquals("chi", c.getId());
        assertTrue(c.canReadBranch("CHI1"));
        assertTrue(c.canReadBranch(" chi1 "));
        assertFalse(c.canReadBranch("DET1"));
        assertFalse(c.canReadBranch(null));
    }

    @Test
    public void rejectsUnknownMissingAndOversizedKeys() {
        ApiClientRegistry r = registry(client("chi", CHI_HASH, "CHI1"));
        assertNull(r.authenticate(null));
        assertNull(r.authenticate(""));
        assertNull(r.authenticate("chi-test-key-0002"));
        assertNull(r.authenticate(CHI_HASH));
        char[] big = new char[ApiClientRegistry.MAX_KEY_LENGTH + 1];
        Arrays.fill(big, 'a');
        assertNull(r.authenticate(new String(big)));
    }

    @Test
    public void blankEntryFromUnsetEnvironmentIsIgnored() {
        ApiClientRegistry r = registry(client("", "", ""));
        assertEquals(0, r.size());
        assertNull(r.authenticate("anything"));
    }

    @Test
    public void entryWithoutKeyHashIsSkipped() {
        ApiClientRegistry r = registry(client("local-dev", "", "*"),
                client("chi", CHI_HASH, "CHI1"));
        assertEquals(1, r.size());
        assertNull(r.authenticate(""));
        assertEquals("chi", r.authenticate("chi-test-key-0001").getId());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsPlaintextKeyInsteadOfHash() {
        registry(client("chi", "chi-test-key-0001", "CHI1"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsClientWithoutBranches() {
        registry(client("chi", CHI_HASH));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateKeys() {
        registry(client("a", CHI_HASH, "CHI1"), client("b", CHI_HASH, "DET1"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsInvalidBranchCode() {
        registry(client("chi", CHI_HASH, "CHI1;DROP"));
    }
}
