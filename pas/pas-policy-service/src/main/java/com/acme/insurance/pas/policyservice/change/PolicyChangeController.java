package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.policyservice.inquiry.PolicyInquiryController;
import com.acme.insurance.pas.policyservice.observability.CorrelationIdFilter;
import com.acme.insurance.pas.policyservice.security.EntitlementResolver;
import com.acme.insurance.pas.rating.PolicyChangeError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renewal and endorsement API (OpenAPI: classpath:openapi/policy-change-v1.yaml). A policy that
 * does not exist or is outside the caller's entitlement answers 404 with an empty body, as in the
 * inquiry API; a refused change answers 422 (409 for a stale expected expiry) with a problem detail
 * whose {@code code} names the reason and whose {@code detail} is the legacy CICS message.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyChangeController {

  static final String METRIC = "pas.policy-change.requests";
  static final int DESCRIPTION_LENGTH = 100;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
  private static final Logger log = LoggerFactory.getLogger(PolicyChangeController.class);

  private final RenewalService renewals;
  private final EndorsementService endorsements;
  private final EntitlementResolver entitlements;
  private final MeterRegistry meters;

  public PolicyChangeController(
      RenewalService renewals,
      EndorsementService endorsements,
      EntitlementResolver entitlements,
      MeterRegistry meters) {
    this.renewals = renewals;
    this.endorsements = endorsements;
    this.entitlements = entitlements;
    this.meters = meters;
  }

  @PostMapping("/{policyNumber}/renewals")
  public ResponseEntity<?> renew(
      @PathVariable String policyNumber,
      @RequestBody(required = false) RenewalRequest request,
      Authentication caller) {
    ChangeResult<RenewalService.Renewal> result =
        renewals.renew(
            policyNumber,
            request == null ? null : request.expectedExpiryDate(),
            entitlements.resolve(caller),
            actor(caller));
    return respond("renewal", caller, policyNumber, result, HttpStatus.OK, RenewalView::from);
  }

  @PostMapping("/{policyNumber}/endorsements")
  public ResponseEntity<?> endorse(
      @PathVariable String policyNumber,
      @RequestBody EndorsementRequest request,
      Authentication caller) {
    if (request.description() != null && request.description().length() > DESCRIPTION_LENGTH) {
      return problem(HttpStatus.BAD_REQUEST, PolicyChangeError.ENDORSEMENT_REQUEST_INVALID);
    }
    BigDecimal change = request.annualPremiumChange();
    if (change != null && (change.scale() > 2 || change.abs().compareTo(MAX_AMOUNT) > 0)) {
      return problem(HttpStatus.BAD_REQUEST, PolicyChangeError.ENDORSEMENT_REQUEST_INVALID);
    }
    var invalidType = PolicyChangeError.checkEndorsementType(request.endorsementType());
    if (invalidType.isPresent()) {
      return problem(HttpStatus.BAD_REQUEST, invalidType.get());
    }
    ChangeResult<EndorsementService.EndorsementOutcome> result =
        endorsements.endorse(
            policyNumber,
            new EndorsementService.EndorsementCommand(
                request.endorsementType(), request.description(), change),
            entitlements.resolve(caller),
            actor(caller));
    return respond(
        "endorsement", caller, policyNumber, result, HttpStatus.CREATED, EndorsementView::from);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
    return problem(HttpStatus.BAD_REQUEST, null);
  }

  private <T, V> ResponseEntity<?> respond(
      String operation,
      Authentication caller,
      String policyNumber,
      ChangeResult<T> result,
      HttpStatus success,
      Function<T, V> view) {
    String client = PolicyInquiryController.clientId(caller);
    Counter.builder(METRIC)
        .description("Renewal and endorsement requests by operation, outcome and calling client")
        .tag("operation", operation)
        .tag("outcome", result.outcome().name().toLowerCase(Locale.ROOT))
        .tag("client", client)
        .register(meters)
        .increment();
    if (result.outcome() == ChangeResult.Outcome.DENIED) {
      log.info(
          "policy change denied by entitlement: operation={} client={} policyNumber={}",
          operation,
          client,
          policyNumber);
    }
    return switch (result.outcome()) {
      case DONE -> ResponseEntity.status(success).body(view.apply(result.value()));
      case NOT_FOUND, DENIED -> ResponseEntity.notFound().build();
      case REJECTED -> problem(HttpStatus.UNPROCESSABLE_ENTITY, result.error());
      case CONFLICT -> problem(HttpStatus.CONFLICT, result.error());
    };
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, PolicyChangeError error) {
    ProblemDetail body = ProblemDetail.forStatus(status);
    body.setProperty("code", error == null ? "REQUEST_INVALID" : error.name());
    if (error != null) {
      body.setDetail(error.legacyText());
    }
    return ResponseEntity.status(status).body(body);
  }

  private static Actor actor(Authentication caller) {
    return new Actor(
        caller.getName(),
        PolicyInquiryController.clientId(caller),
        MDC.get(CorrelationIdFilter.MDC_KEY));
  }
}
