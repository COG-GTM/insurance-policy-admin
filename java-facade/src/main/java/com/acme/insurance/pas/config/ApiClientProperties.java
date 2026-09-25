package com.acme.insurance.pas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API client registry for the facade.
 *
 * Each client authenticates with HTTP Basic credentials and carries the
 * entitlements that scope which policies it may read. Configure with:
 *
 *   pas.security.clients[0].username=claims-engine
 *   pas.security.clients[0].password-hash=${CLAIMS_ENGINE_PASSWORD_HASH}
 *   pas.security.clients[0].agent-codes=AG1001,AG1002
 *   pas.security.clients[1].username=policy-batch
 *   pas.security.clients[1].password-hash=${POLICY_BATCH_PASSWORD_HASH}
 *   pas.security.clients[1].unrestricted=true
 *
 * Passwords are stored as BCrypt hashes only ($2a format, the one supported
 * by Spring Security 4.2); plaintext is never accepted.
 */
@Component
@ConfigurationProperties(prefix = "pas.security")
public class ApiClientProperties {

    private List<ApiClient> clients = new ArrayList<ApiClient>();

    public List<ApiClient> getClients() {
        return clients;
    }

    public void setClients(List<ApiClient> clients) {
        this.clients = clients;
    }

    public static class ApiClient {

        private String username;
        private String passwordHash;

        /**
         * Grants access to every policy. Reserved for internal batch/extract
         * clients that legitimately process the whole book of business.
         */
        private boolean unrestricted;

        /** Agent codes whose policies this client may read. */
        private List<String> agentCodes = new ArrayList<String>();

        /** Policyholder ids whose policies this client may read. */
        private List<String> policyholderIds = new ArrayList<String>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public boolean isUnrestricted() {
            return unrestricted;
        }

        public void setUnrestricted(boolean unrestricted) {
            this.unrestricted = unrestricted;
        }

        public List<String> getAgentCodes() {
            return agentCodes;
        }

        public void setAgentCodes(List<String> agentCodes) {
            this.agentCodes = agentCodes == null
                    ? new ArrayList<String>() : agentCodes;
        }

        public List<String> getPolicyholderIds() {
            return policyholderIds;
        }

        public void setPolicyholderIds(List<String> policyholderIds) {
            this.policyholderIds = policyholderIds == null
                    ? new ArrayList<String>() : policyholderIds;
        }
    }
}
