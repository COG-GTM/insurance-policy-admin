package com.acme.insurance.pas.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "pas.security")
public class ApiClientProperties {

    private List<Client> clients = new ArrayList<Client>();

    public List<Client> getClients() {
        return clients;
    }

    public void setClients(List<Client> clients) {
        this.clients = clients;
    }

    public static class Client {
        private String id;
        private String keySha256;
        private List<String> branchCodes = new ArrayList<String>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getKeySha256() {
            return keySha256;
        }

        public void setKeySha256(String keySha256) {
            this.keySha256 = keySha256;
        }

        public List<String> getBranchCodes() {
            return branchCodes;
        }

        public void setBranchCodes(List<String> branchCodes) {
            this.branchCodes = branchCodes;
        }
    }
}
