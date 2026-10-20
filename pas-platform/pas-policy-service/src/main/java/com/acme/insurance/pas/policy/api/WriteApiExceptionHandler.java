package com.acme.insurance.pas.policy.api;

import com.acme.insurance.pas.common.error.ApiError;
import com.acme.insurance.pas.policy.claims.ClaimsHistoryUnavailableException;
import com.acme.insurance.pas.policy.lifecycle.EndorsementController;
import com.acme.insurance.pas.policy.lifecycle.NewBusinessController;
import com.acme.insurance.pas.policy.lifecycle.NewBusinessRejectedException;
import com.acme.insurance.pas.policy.lifecycle.PolicyChangeRejectedException;
import com.acme.insurance.pas.policy.lifecycle.RenewalController;
import com.acme.insurance.pas.policy.security.NotEntitledException;
import com.acme.insurance.pas.policy.support.CorrelationIds;
import com.acme.insurance.pas.policy.underwriting.NoDecisionToOverrideException;
import com.acme.insurance.pas.policy.underwriting.UnderwritingController;
import com.acme.insurance.pas.policy.underwriting.UnderwritingDataIncompleteException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** {@link ApiError} responses for the AB-525 write APIs (the inquiry API is unaffected). */
@RestControllerAdvice(
    assignableTypes = {
      NewBusinessController.class,
      UnderwritingController.class,
      RenewalController.class,
      EndorsementController.class
    })
public class WriteApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(WriteApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED",
        "request body is not valid JSON for this operation",
        request);
  }

  @ExceptionHandler(InvalidRequestException.class)
  ResponseEntity<ApiError> badRequest(InvalidRequestException e, HttpServletRequest request) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), request);
  }

  @ExceptionHandler(NotEntitledException.class)
  ResponseEntity<ApiError> notEntitled(NotEntitledException e, HttpServletRequest request) {
    return error(HttpStatus.FORBIDDEN, "NOT_ENTITLED", e.getMessage(), request);
  }

  @ExceptionHandler(NewBusinessRejectedException.class)
  ResponseEntity<ApiError> rejected(NewBusinessRejectedException e, HttpServletRequest request) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage(), request);
  }

  @ExceptionHandler(PolicyChangeRejectedException.class)
  ResponseEntity<ApiError> changeRejected(
      PolicyChangeRejectedException e, HttpServletRequest request) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, e.code(), e.getMessage(), request);
  }

  @ExceptionHandler(UnderwritingDataIncompleteException.class)
  ResponseEntity<ApiError> incomplete(
      UnderwritingDataIncompleteException e, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY, "UNDERWRITING_DATA_INCOMPLETE", e.getMessage(), request);
  }

  @ExceptionHandler(NoDecisionToOverrideException.class)
  ResponseEntity<ApiError> noDecision(NoDecisionToOverrideException e, HttpServletRequest request) {
    return error(HttpStatus.CONFLICT, "NO_DECISION_TO_OVERRIDE", e.getMessage(), request);
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ApiError> concurrent(
      ObjectOptimisticLockingFailureException e, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "CONCURRENT_UPDATE",
        "the policy changed concurrently; retry",
        request);
  }

  @ExceptionHandler(ClaimsHistoryUnavailableException.class)
  ResponseEntity<ApiError> claimsUnavailable(
      ClaimsHistoryUnavailableException e, HttpServletRequest request) {
    log.warn("claims history unavailable: {}", e.getMessage());
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CLAIMS_HISTORY_UNAVAILABLE",
        "claims history is unavailable; the policy was not changed",
        request);
  }

  private static ResponseEntity<ApiError> error(
      HttpStatus status, String code, String message, HttpServletRequest request) {
    String traceId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER));
    return ResponseEntity.status(status)
        .header(CorrelationIds.HEADER, traceId)
        .body(ApiError.of(code, message, request.getRequestURI(), traceId));
  }
}
