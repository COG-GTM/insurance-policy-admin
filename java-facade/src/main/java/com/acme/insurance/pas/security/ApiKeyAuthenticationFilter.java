package com.acme.insurance.pas.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Authenticates the {@code X-API-Key} header against {@link ApiClientRegistry}.
 * Requests without a valid key stay anonymous and are rejected by the
 * security configuration for {@code /api/**}.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String ROLE = "PAS_CLIENT";

    private final ApiClientRegistry registry;

    public ApiKeyAuthenticationFilter(ApiClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ApiClient client = registry.authenticate(request.getHeader(HEADER));
        if (client != null) {
            SecurityContextHolder.getContext().setAuthentication(new ApiClientAuthentication(client));
        }
        chain.doFilter(request, response);
    }

    /** Authentication token whose principal is the resolved {@link ApiClient}. */
    public static class ApiClientAuthentication extends AbstractAuthenticationToken {

        private final ApiClient client;

        public ApiClientAuthentication(ApiClient client) {
            super(AuthorityUtils.createAuthorityList("ROLE_" + ROLE));
            this.client = client;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public ApiClient getPrincipal() {
            return client;
        }

        @Override
        public String getName() {
            return client.getId();
        }
    }
}
