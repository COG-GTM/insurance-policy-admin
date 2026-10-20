package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.common.error.ApiError;
import com.acme.insurance.pas.policy.api.InvalidRequestException;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.security.JwtSubjects;
import com.acme.insurance.pas.policy.security.PasScopes;
import com.acme.insurance.pas.policy.support.CorrelationIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** New business v1: replaces CICS PNEW (POLNEW). */
@RestController
@RequestMapping(path = "/api/v1/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "New business", description = "Policy creation (replaces POLNEW)")
@SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.POLICY_WRITE)
public class NewBusinessController {

  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final String REPLAYED = "Idempotent-Replayed";
  private static final int MAX_KEY_LENGTH = 255;

  private final NewBusinessService service;

  public NewBusinessController(NewBusinessService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "createPolicy",
      summary = "Create a policy",
      description =
          "Validates like POLNEW (422 with the COBOL message, last failing check wins), assigns a"
              + " POL + 9-digit policy number, writes default coverages for the policy type and"
              + " publishes PolicyCreated. Status PN, underwriting status PN.")
  @ApiResponse(responseCode = "201", description = "Policy created (or replayed)")
  @ApiResponse(
      responseCode = "400",
      description = "Malformed request",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "401",
      description = "Missing or invalid bearer token",
      content = @Content(schema = @Schema(hidden = true)))
  @ApiResponse(
      responseCode = "403",
      description = "Token lacks scope policy:write, or not entitled to the agent/branch",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "422",
      description = "POLNEW validation failed, invalid term, or Idempotency-Key reused",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ResponseEntity<CreatedPolicyView> createPolicy(
      @Valid @RequestBody CreatePolicyRequest request,
      @Parameter(description = "Client-chosen key; a repeat returns the first result")
          @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
          String idempotencyKey,
      @Parameter(description = "Propagated to events as correlationId")
          @RequestHeader(name = CorrelationIds.HEADER, required = false)
          String correlationHeader,
      @AuthenticationPrincipal Jwt jwt) {
    if (idempotencyKey != null
        && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH)) {
      throw new InvalidRequestException(
          IDEMPOTENCY_KEY + " must be 1-" + MAX_KEY_LENGTH + " characters");
    }
    String correlationId = CorrelationIds.resolve(correlationHeader);
    NewBusinessService.Outcome outcome =
        service.create(
            request,
            CallerEntitlements.fromJwt(jwt),
            JwtSubjects.checkLength(Objects.requireNonNullElse(jwt.getSubject(), "")),
            idempotencyKey,
            correlationId);
    String number = outcome.view().policy().policyNumber();
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/v1/policies/" + number))
        .header(CorrelationIds.HEADER, correlationId)
        .header(REPLAYED, Boolean.toString(outcome.replayed()))
        .body(outcome.view());
  }
}
