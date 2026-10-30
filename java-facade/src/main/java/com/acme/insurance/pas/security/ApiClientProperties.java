package com.acme.insurance.pas.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumers allowed to call the policy API ({@code pas.security.clients[n]}).
 *
 * Only the SHA-256 hex digest of each client's API key is configured; the key
 * itself is sent by the client in the {@code X-API-Key} header. {@code branches}
 * lists the BRANCH_CODE values the client may read, or {@code *} for all.
 */
@ConfigurationProperties(prefix = "pas.security")
public class ApiClientProperties {

    private List<Client> clients = new ArrayList<Client>();

    public List<Client> getClients() { return clients; }
    public void setClients(List<Client> clients) { this.clients = clients; }

    public static class Client {
        private String id;
        private String keySha256;
        private List<String> branches = new ArrayList<String>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getKeySha256() { return keySha256; }
        public void setKeySha256(String keySha256) { this.keySha256 = keySha256; }

        public List<String> getBranches() { return branches; }
        public void setBranches(List<String> branches) { this.branches = branches; }
    }
}
