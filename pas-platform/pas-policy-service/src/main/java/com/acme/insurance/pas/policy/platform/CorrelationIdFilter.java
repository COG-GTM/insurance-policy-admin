package com.acme.insurance.pas.policy.platform;

import com.acme.insurance.pas.policy.events.CorrelationIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String correlationId = CorrelationIds.fromHeader(request.getHeader(CorrelationIds.HEADER_NAME));
    request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(CorrelationIds.HEADER_NAME, correlationId);
    filterChain.doFilter(request, response);
  }
}
