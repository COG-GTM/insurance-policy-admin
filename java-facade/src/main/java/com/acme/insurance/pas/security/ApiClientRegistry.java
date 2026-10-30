package com.acme.insurance.pas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves an {@code X-API-Key} value to a configured {@link ApiClient}.
 *
 * Fails application startup on an invalid client entry so a misconfiguration
 * cannot silently widen access. Entries without a key hash (unset environment
 * variable) are skipped; with no clients every request is rejected.
 */
@Component
public class ApiClientRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ApiClientRegistry.class);

    static final int MAX_KEY_LENGTH = 512;

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern BRANCH = Pattern.compile("[A-Z0-9]{1,4}|\\*");

    private final List<Entry> entries = new ArrayList<Entry>();

    @Autowired
    public ApiClientRegistry(ApiClientProperties properties) {
        Set<String> ids = new HashSet<String>();
        Set<String> hashes = new HashSet<String>();
        for (ApiClientProperties.Client client : properties.getClients()) {
            String id = trim(client.getId());
            String hash = trim(client.getKeySha256()).toLowerCase(Locale.ROOT);
            Set<String> branches = normaliseBranches(client.getBranches());
            if (hash.isEmpty()) {
                if (!id.isEmpty() || !branches.isEmpty()) {
                    LOG.warn("Policy API client '{}' has no key-sha256 configured; skipping",
                            AuditLog.clean(id));
                }
                continue;
            }
            if (!CLIENT_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "pas.security.clients: invalid or missing client id '" + id + "'");
            }
            if (!SHA256_HEX.matcher(hash).matches()) {
                throw new IllegalStateException("pas.security.clients[" + id
                        + "].key-sha256 must be a 64-character SHA-256 hex digest");
            }
            if (branches.isEmpty()) {
                throw new IllegalStateException("pas.security.clients[" + id
                        + "].branches must list branch codes or '*'");
            }
            for (String branch : branches) {
                if (!BRANCH.matcher(branch).matches()) {
                    throw new IllegalStateException("pas.security.clients[" + id
                            + "].branches contains invalid branch code '" + branch + "'");
                }
            }
            if (!ids.add(id)) {
                throw new IllegalStateException("pas.security.clients: duplicate client id '"
                        + id + "'");
            }
            if (!hashes.add(hash)) {
                throw new IllegalStateException("pas.security.clients[" + id
                        + "].key-sha256 is already used by another client");
            }
            entries.add(new Entry(hexToBytes(hash), new ApiClient(id, branches)));
        }
        if (entries.isEmpty()) {
            LOG.warn("No policy API clients configured (pas.security.clients); "
                    + "all /api/** requests will be rejected");
        } else {
            LOG.info("Loaded {} policy API client(s)", entries.size());
        }
    }

    /** Returns the client owning {@code apiKey}, or {@code null}. */
    public ApiClient authenticate(String apiKey) {
        if (apiKey == null || apiKey.isEmpty() || apiKey.length() > MAX_KEY_LENGTH) {
            return null;
        }
        byte[] digest = sha256(apiKey);
        ApiClient match = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.keyHash, digest)) {
                match = entry.client;
            }
        }
        return match;
    }

    public int size() {
        return entries.size();
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> normaliseBranches(List<String> configured) {
        Set<String> branches = new LinkedHashSet<String>();
        if (configured != null) {
            for (String branch : configured) {
                if (StringUtils.hasText(branch)) {
                    branches.add(branch.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return branches;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    private static final class Entry {
        private final byte[] keyHash;
        private final ApiClient client;

        private Entry(byte[] keyHash, ApiClient client) {
            this.keyHash = keyHash;
            this.client = client;
        }
    }
}
