package com.acme.insurance.pas.security;

import com.acme.insurance.pas.config.ApiClientProperties;
import com.acme.insurance.pas.config.ApiClientProperties.ApiClient;
import com.acme.insurance.pas.model.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks whether an API client is entitled to read a policy.
 */
@Service
public class PolicyAuthorizationService {

    private final Map<String, ApiClient> clientsByUsername =
            new HashMap<String, ApiClient>();

    @Autowired
    public PolicyAuthorizationService(ApiClientProperties properties) {
        for (ApiClient client : properties.getClients()) {
            if (client != null && client.getUsername() != null) {
                clientsByUsername.put(client.getUsername(), client);
            }
        }
    }

    public boolean isAuthorized(String username, Policy policy) {
        ApiClient client = clientsByUsername.get(username);
        if (client == null || policy == null) {
            return false;
        }
        if (client.isUnrestricted()) {
            return true;
        }
        return contains(client.getAgentCodes(), policy.getAgentCode())
                || contains(client.getBranchCodes(), policy.getBranchCode());
    }

    private static boolean contains(List<String> allowed, String value) {
        if (allowed == null || value == null) {
            return false;
        }
        for (String candidate : allowed) {
            if (candidate != null && value.equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }
}
