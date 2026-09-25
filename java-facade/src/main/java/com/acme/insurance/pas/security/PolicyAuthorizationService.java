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
 * Decides whether the authenticated API client is entitled to a policy.
 *
 * Authentication alone is not enough: policy numbers are short and
 * sequential, so a client must additionally be scoped to the agents or
 * policyholders whose policies it may read. Clients flagged as unrestricted
 * (internal batch/extract jobs) see the whole book of business.
 */
@Service
public class PolicyAuthorizationService {

    private final Map<String, ApiClient> clientsByUsername =
            new HashMap<String, ApiClient>();

    @Autowired
    public PolicyAuthorizationService(ApiClientProperties properties) {
        for (ApiClient client : properties.getClients()) {
            clientsByUsername.put(client.getUsername(), client);
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
                || contains(client.getPolicyholderIds(),
                        policy.getPolicyholderId());
    }

    private static boolean contains(List<String> allowed, String value) {
        if (value == null) {
            return false;
        }
        for (String candidate : allowed) {
            if (value.equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }
}
