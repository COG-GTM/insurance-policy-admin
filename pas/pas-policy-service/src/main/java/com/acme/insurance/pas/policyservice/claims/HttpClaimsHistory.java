package com.acme.insurance.pas.policyservice.claims;

import com.acme.insurance.pas.underwriting.ClaimsHistory;
import com.acme.insurance.pas.underwriting.ClaimsHistoryUnavailableException;
import com.acme.insurance.pas.underwriting.ClaimsSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Claims history over HTTP: {@code GET /v1/policies/{policyNumber}/claims-summary?from=&to=}
 * answering {@code {"policyNumber", "claimCount", "totalIncurred"}} (proposed contract, see
 * docs/pas/new-business-underwriting.md; to be agreed with the Claims team, FU-17). Replaces
 * UNDWRT's read of the Claims-owned table. Any failure or an incomplete answer is {@link
 * ClaimsHistoryUnavailableException}.
 */
public class HttpClaimsHistory implements ClaimsHistory {

  /** Response body of the claims-summary endpoint. */
  public record Summary(String policyNumber, Integer claimCount, BigDecimal totalIncurred) {}

  private final RestClient http;

  public HttpClaimsHistory(RestClient http) {
    this.http = http;
  }

  @Override
  public ClaimsSummary summary(String policyNumber, LocalDate from, LocalDate to) {
    Summary body;
    try {
      body =
          http.get()
              .uri(
                  b ->
                      b.path("/v1/policies/{policyNumber}/claims-summary")
                          .queryParam("from", from)
                          .queryParam("to", to)
                          .build(policyNumber))
              .retrieve()
              .body(Summary.class);
    } catch (RestClientException e) {
      throw new ClaimsHistoryUnavailableException(
          "claims history request failed: " + e.getClass().getSimpleName(), e);
    }
    if (body == null
        || body.policyNumber() == null
        || body.claimCount() == null
        || body.totalIncurred() == null) {
      throw new ClaimsHistoryUnavailableException("claims history answer is incomplete");
    }
    if (body.claimCount() < 0) {
      throw new ClaimsHistoryUnavailableException("claims history answered a negative claim count");
    }
    if (!body.policyNumber().equals(policyNumber)) {
      throw new ClaimsHistoryUnavailableException("claims history answered for another policy");
    }
    return new ClaimsSummary(body.claimCount(), body.totalIncurred());
  }
}
