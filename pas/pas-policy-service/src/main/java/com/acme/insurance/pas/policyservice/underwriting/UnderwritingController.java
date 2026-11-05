package com.acme.insurance.pas.policyservice.underwriting;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.policyservice.newbusiness.ApiError;
import com.acme.insurance.pas.policyservice.observability.CorrelationIdFilter;
import com.acme.insurance.pas.policyservice.security.CallerIdentity;
import com.acme.insurance.pas.policyservice.security.EntitlementResolver;
import com.acme.insurance.pas.underwriting.ClaimsHistoryUnavailableException;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Underwriting API (OpenAPI: classpath:openapi/new-business-underwriting-v1.yaml): run the decision
 * ({@code pas.underwriting.decide}), override it ({@code pas.underwriting.override}) and list the
 * decision history ({@code pas.policy.read}). Policies outside the caller's entitlement answer 404
 * like missing ones.
 */
@RestController
@RequestMapping("/api/v1/policies/{policyNumber}/underwriting-decisions")
public class UnderwritingController {

  private static final Logger LOG = LoggerFactory.getLogger(UnderwritingController.class);

  private final UnderwritingService service;
  private final EntitlementResolver entitlements;

  public UnderwritingController(UnderwritingService service, EntitlementResolver entitlements) {
    this.service = service;
    this.entitlements = entitlements;
  }

  /** Override request body. */
  public record OverrideRequest(String decisionCode, String reason) {}

  /** One decision as returned by the API. */
  public record DecisionView(
      String policyNumber,
      LocalDate decisionDate,
      String decisionCode,
      Integer riskScore,
      String decisionReason,
      String underwriterId,
      String overrideReason,
      String overrideBy,
      List<String> reasons) {

    public DecisionView {
      reasons = reasons == null ? null : List.copyOf(reasons);
    }

    static DecisionView of(UnderwritingDecision d, List<String> reasons) {
      return new DecisionView(
          d.policyNumber(),
          d.decisionDate(),
          d.decisionCode(),
          d.riskScore(),
          d.decisionReason(),
          d.underwriterId(),
          d.overrideReason(),
          d.overrideBy(),
          reasons);
    }
  }

  @PostMapping
  public ResponseEntity<?> decide(@PathVariable String policyNumber, Authentication caller) {
    return respond(
        service.decide(
            policyNumber,
            entitlements.resolve(caller),
            CallerIdentity.of(caller),
            MDC.get(CorrelationIdFilter.MDC_KEY)),
        HttpStatus.CREATED);
  }

  @PostMapping("/override")
  public ResponseEntity<?> override(
      @PathVariable String policyNumber,
      @RequestBody OverrideRequest request,
      Authentication caller) {
    return respond(
        service.override(
            policyNumber,
            request.decisionCode(),
            request.reason(),
            entitlements.resolve(caller),
            CallerIdentity.of(caller),
            MDC.get(CorrelationIdFilter.MDC_KEY)),
        HttpStatus.OK);
  }

  @GetMapping
  public ResponseEntity<List<DecisionView>> history(
      @PathVariable String policyNumber, Authentication caller) {
    return service
        .decisions(policyNumber, entitlements.resolve(caller))
        .map(
            list ->
                ResponseEntity.ok(
                    list.reversed().stream().map(d -> DecisionView.of(d, null)).toList()))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @ExceptionHandler(ClaimsHistoryUnavailableException.class)
  ResponseEntity<ApiError> claimsUnavailable(ClaimsHistoryUnavailableException e) {
    LOG.warn("underwriting stopped, claims history unavailable: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError("CLAIMS HISTORY UNAVAILABLE, RETRY LATER", List.of()));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<ApiError> duplicate(DuplicateKeyException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError("UNDERWRITING DECISION ALREADY RECORDED TODAY", List.of()));
  }

  private static ResponseEntity<?> respond(UnderwritingResult result, HttpStatus success) {
    return switch (result) {
      case UnderwritingResult.Done d ->
          ResponseEntity.status(success).body(DecisionView.of(d.decision(), d.reasons()));
      case UnderwritingResult.Invalid i ->
          ResponseEntity.badRequest()
              .body(
                  new ApiError(
                      i.message(),
                      i.violations().stream().map(v -> new ApiError.Violation(null, v)).toList()));
      case UnderwritingResult.NotFound n -> ResponseEntity.notFound().build();
      case UnderwritingResult.Conflict c ->
          ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(c.message(), List.of()));
    };
  }
}
