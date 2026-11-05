package com.acme.insurance.pas.policyservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PAS accepts bearer tokens only. Spring Security 6.5 wires DPoP authentication into every JWT
 * resource server, and its DPoP proof replay cache is affected by CVE-2026-41707 (no OSS fix on
 * 6.5). Requests using the DPoP scheme or carrying a DPoP proof are answered 401 before any
 * authentication filter runs, so that code path is unreachable (FU-524-4).
 */
public class DpopRejectingFilter extends OncePerRequestFilter {

  static final String DPOP = "DPoP";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (usesDpop(request)) {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE,
          "Bearer error=\"invalid_request\", error_description=\"DPoP is not supported\"");
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean usesDpop(HttpServletRequest request) {
    if (request.getHeader(DPOP) != null) {
      return true;
    }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (!StringUtils.hasText(authorization)) {
      return false;
    }
    String scheme = authorization.trim().split("\\s+", 2)[0];
    return DPOP.equalsIgnoreCase(scheme);
  }
}
