package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException;
import com.acme.insurance.pas.policy.endorsement.EndorsementController;
import com.acme.insurance.pas.policy.renewal.RenewalController;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps renewal/endorsement failures to RFC 9457 problem responses. Messages carry no PII. */
@RestControllerAdvice(assignableTypes = {RenewalController.class, EndorsementController.class})
public class LifecycleExceptionHandler {
  private static final String TYPE_BASE = "urn:pas:problem:";

  @ExceptionHandler(PolicyNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(PolicyNotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(LifecycleRejectedException.class)
  ResponseEntity<ProblemDetail> rejected(LifecycleRejectedException exception) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        exception.rejection().name(),
        exception.rejection().message());
  }

  @ExceptionHandler(LifecycleConflictException.class)
  ResponseEntity<ProblemDetail> conflict(LifecycleConflictException exception) {
    return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
  }

  @ExceptionHandler(ClaimsUnavailableException.class)
  ResponseEntity<ProblemDetail> claimsUnavailable(ClaimsUnavailableException exception) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "CLAIMS_UNAVAILABLE", exception.getMessage());
  }

  @ExceptionHandler(RouteNotServingException.class)
  ResponseEntity<ProblemDetail> routeNotServing(RouteNotServingException exception) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NOT_SERVING", exception.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_BASE + code.toLowerCase(java.util.Locale.ROOT)));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    return ResponseEntity.status(status).body(problem);
  }
}
