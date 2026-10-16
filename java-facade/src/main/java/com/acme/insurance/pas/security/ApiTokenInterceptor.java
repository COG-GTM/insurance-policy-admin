package com.acme.insurance.pas.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Bearer-token interceptor for the /api/** endpoints.
 *
 * Validates the Authorization header against the shared service tokens
 * configured via pas.security.api-tokens (comma-separated, to allow
 * rotation). Fails closed: with no tokens configured every request
 * is rejected. Comparison uses MessageDigest.isEqual against all
 * configured tokens without early return.
 *
 * @author T. Nguyen (2022)
 */
public class ApiTokenInterceptor extends HandlerInterceptorAdapter {

    private static final Logger LOG =
            LoggerFactory.getLogger(ApiTokenInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final List<byte[]> tokens = new ArrayList<byte[]>();

    public ApiTokenInterceptor(String apiTokens) {
        if (apiTokens != null) {
            for (String entry : apiTokens.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    tokens.add(trimmed.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if (tokens.isEmpty()) {
            LOG.warn("no API tokens configured; all /api requests "
                    + "will be rejected");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        byte[] presented = presentedToken(request);
        boolean authorized = false;
        if (presented != null) {
            for (byte[] configured : tokens) {
                if (MessageDigest.isEqual(presented, configured)) {
                    authorized = true;
                }
            }
        }
        if (!authorized) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate",
                    "Bearer realm=\"pas-facade\"");
            return false;
        }
        return true;
    }

    private byte[] presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null
                || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0,
                        BEARER_PREFIX.length())) {
            return null;
        }
        String credential = header.substring(BEARER_PREFIX.length());
        if (credential.trim().isEmpty()) {
            return null;
        }
        return credential.getBytes(StandardCharsets.UTF_8);
    }
}
