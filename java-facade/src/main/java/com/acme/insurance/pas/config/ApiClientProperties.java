package com.acme.insurance.pas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * API client credentials and policy entitlements.
 *
 * @author T. Nguyen (2022)
 */
@Component
@ConfigurationProperties(prefix = "pas.security")
public class ApiClientProperties {

    private List<ApiClient> clients = new ArrayList<ApiClient>();

    public List<ApiClient> getClients() {
        return clients;
    }

    public void setClients(List<ApiClient> clients) {
        this.clients = clients == null
                ? new ArrayList<ApiClient>() : clients;
    }

    public static class ApiClient {

        private String username;
        private String passwordHash;
        private boolean unrestricted;
        private List<String> agentCodes = new ArrayList<String>();
        private List<String> branchCodes = new ArrayList<String>();

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

        public List<String> getBranchCodes() {
            return branchCodes;
        }

        public void setBranchCodes(List<String> branchCodes) {
            this.branchCodes = branchCodes == null
                    ? new ArrayList<String>() : branchCodes;
        }
    }
}
