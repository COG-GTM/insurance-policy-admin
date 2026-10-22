package com.acme.insurance.pas.policy.platform;

import com.acme.insurance.pas.policy.claims.ClaimsHistoryUnavailableException;
import com.acme.insurance.pas.policy.newbusiness.PolicyNumberCollisionException;
import com.acme.insurance.pas.policy.newbusiness.PolicyNumberExhaustedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiProblem> malformedRequest() {
    return problem(HttpStatus.BAD_REQUEST, "Request body is malformed");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiProblem> invalidRouteParameter() {
    return problem(HttpStatus.BAD_REQUEST, "A route parameter is invalid");
  }

  @ExceptionHandler(PolicyNumberExhaustedException.class)
  ResponseEntity<ApiProblem> policyNumberExhausted() {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Policy number sequence is exhausted");
  }

  @ExceptionHandler(PolicyNumberCollisionException.class)
  ResponseEntity<ApiProblem> policyNumberCollision() {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to allocate a unique policy number");
  }

  @ExceptionHandler(ApiRequestException.class)
  ResponseEntity<ApiProblem> requestFailure(ApiRequestException exception) {
    ApiProblem body =
        new ApiProblem(
            "about:blank",
            exception.status().getReasonPhrase(),
            exception.status().value(),
            exception.getMessage(),
            exception.errors());
    return ResponseEntity.status(exception.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  @ExceptionHandler(ClaimsHistoryUnavailableException.class)
  ResponseEntity<ApiProblem> claimsUnavailable() {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Claims history service is unavailable");
  }

  private static ResponseEntity<ApiProblem> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblem.of(status.value(), status.getReasonPhrase(), detail));
  }
}
