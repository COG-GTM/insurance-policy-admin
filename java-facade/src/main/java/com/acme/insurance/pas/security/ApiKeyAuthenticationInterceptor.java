package com.acme.insurance.pas.security;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ApiKeyAuthenticationInterceptor extends HandlerInterceptorAdapter {

    private final ApiClientProperties clientProperties;

    public ApiKeyAuthenticationInterceptor(ApiClientProperties clientProperties) {
        this.clientProperties = clientProperties;
    }

    @PostConstruct
    void validateClients() {
        List<ApiClientProperties.Client> clients = clientProperties.getClients();
        if (clients == null) {
            return;
        }

        for (int i = 0; i < clients.size(); i++) {
            ApiClientProperties.Client client = clients.get(i);
            if (client == null) {
                continue;
            }
            byte[] keyHash = decodeSha256(client.getKeySha256());
            if (keyHash == null) {
                continue;
            }
            for (int previousIndex = 0; previousIndex < i; previousIndex++) {
                ApiClientProperties.Client previous = clients.get(previousIndex);
                if (previous == null) {
                    continue;
                }
                byte[] previousHash = decodeSha256(previous.getKeySha256());
                if (previousHash != null
                        && MessageDigest.isEqual(keyHash, previousHash)) {
                    throw new IllegalStateException(
                            "Duplicate API key hash configured for clients: "
                                    + previous.getId() + ", " + client.getId());
                }
            }
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String key = bearerKey(request.getHeader("Authorization"));
        if (key == null) {
            return unauthorized(response);
        }

        byte[] suppliedHash = sha256(key);
        ApiClientProperties.Client matchedClient = null;
        int matches = 0;
        List<ApiClientProperties.Client> clients = clientProperties.getClients();
        if (clients != null) {
            for (ApiClientProperties.Client client : clients) {
                if (client == null) {
                    continue;
                }
                byte[] configuredHash = decodeSha256(client.getKeySha256());
                if (configuredHash != null
                        && MessageDigest.isEqual(suppliedHash, configuredHash)) {
                    matchedClient = client;
                    matches++;
                }
            }
        }

        if (matches != 1 || matchedClient == null) {
            return unauthorized(response);
        }

        Set<String> branchCodes = new HashSet<String>();
        List<String> configuredBranches = matchedClient.getBranchCodes();
        if (configuredBranches != null) {
            for (String branchCode : configuredBranches) {
                if (branchCode != null && !branchCode.trim().isEmpty()) {
                    branchCodes.add(branchCode.trim());
                }
            }
        }
        request.setAttribute(ApiClient.REQUEST_ATTRIBUTE,
                new ApiClient(matchedClient.getId(), branchCodes));
        return true;
    }

    private static boolean unauthorized(HttpServletResponse response)
            throws Exception {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"pas-facade\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    private static String bearerKey(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String header = authorizationHeader.trim();
        int separator = header.indexOf(' ');
        if (separator < 0
                || !"Bearer".equalsIgnoreCase(header.substring(0, separator))) {
            return null;
        }
        String key = header.substring(separator + 1).trim();
        return key.isEmpty() ? null : key;
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static byte[] decodeSha256(String hash) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            return null;
        }
        byte[] decoded = new byte[32];
        for (int i = 0; i < decoded.length; i++) {
            int offset = i * 2;
            decoded[i] = (byte) Integer.parseInt(hash.substring(offset, offset + 2), 16);
        }
        return decoded;
    }
}
