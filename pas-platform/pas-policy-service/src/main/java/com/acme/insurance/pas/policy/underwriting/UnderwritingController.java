package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.common.error.ApiError;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.security.NotEntitledException;
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
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Underwriting v1: replaces CICS PUWR (UNDWRT) and adds audited manual overrides. */
@RestController
@RequestMapping(
    path = "/api/v1/policies/{policyNumber}/underwriting-decisions",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Underwriting", description = "Underwriting decisions (replaces UNDWRT)")
@ApiResponse(
    responseCode = "401",
    description = "Missing or invalid bearer token",
    content = @Content(schema = @Schema(hidden = true)))
@ApiResponse(
    responseCode = "404",
    description = "Not found or not entitled",
    content = @Content(schema = @Schema(hidden = true)))
public class UnderwritingController {

  private final UnderwritingService service;

  public UnderwritingController(UnderwritingService service) {
    this.service = service;
  }

  @PostMapping
  @SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.UNDERWRITING_DECIDE)
  @Operation(
      operationId = "decideUnderwriting",
      summary = "Run the automated underwriting decision",
      description =
          "UNDWRT risk score at parity: AP < 300, RS < 600, RM < 800, else DC. Writes an audit row,"
              + " updates the policy's uwStatus/riskScore and publishes UnderwritingReferred (RS/RM)"
              + " or UnderwritingDecided (AP/DC).")
  @ApiResponse(responseCode = "201", description = "Decision recorded")
  @ApiResponse(
      responseCode = "403",
      description = "Token lacks scope underwriting:decide",
      content = @Content(schema = @Schema(hidden = true)))
  @ApiResponse(
      responseCode = "409",
      description = "Concurrent update of the policy",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "422",
      description = "Policy/policyholder data incomplete (POLICY/CUSTOMER DATA NOT FOUND)",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Claims history unavailable",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ResponseEntity<UnderwritingDecisionView> decideUnderwriting(
      @Parameter(example = "POL001000000") @PathVariable String policyNumber,
      @RequestHeader(name = CorrelationIds.HEADER, required = false) String correlationHeader,
      @AuthenticationPrincipal Jwt jwt) {
    String correlationId = CorrelationIds.resolve(correlationHeader);
    return created(
        service.decide(policyNumber, CallerEntitlements.fromJwt(jwt), correlationId),
        correlationId);
  }

  @PostMapping(path = "/overrides", consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.UNDERWRITING_DECIDE)
  @Operation(
      operationId = "overrideUnderwriting",
      summary = "Manually override the latest underwriting decision",
      description =
          "Records a new decision with the reason and the caller's JWT subject as overrideBy;"
              + " keeps the latest risk score.")
  @ApiResponse(responseCode = "201", description = "Override recorded")
  @ApiResponse(
      responseCode = "400",
      description = "Malformed request",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "403",
      description = "Token lacks scope underwriting:decide or has no subject",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "409",
      description = "No decision to override, or concurrent update",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ResponseEntity<UnderwritingDecisionView> overrideUnderwriting(
      @Parameter(example = "POL001000000") @PathVariable String policyNumber,
      @Valid @RequestBody OverrideRequest request,
      @RequestHeader(name = CorrelationIds.HEADER, required = false) String correlationHeader,
      @AuthenticationPrincipal Jwt jwt) {
    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new NotEntitledException("a manual override needs a token with a subject");
    }
    String correlationId = CorrelationIds.resolve(correlationHeader);
    return created(
        service.override(
            policyNumber, CallerEntitlements.fromJwt(jwt), subject, request, correlationId),
        correlationId);
  }

  @GetMapping
  @SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.POLICY_READ)
  @Operation(
      operationId = "listUnderwritingDecisions",
      summary = "List a policy's underwriting decisions (audit trail), oldest first")
  @ApiResponse(responseCode = "200", description = "Decisions")
  @ApiResponse(
      responseCode = "403",
      description = "Token lacks scope policy:read",
      content = @Content(schema = @Schema(hidden = true)))
  public ResponseEntity<List<UnderwritingDecisionView>> listUnderwritingDecisions(
      @Parameter(example = "POL001000000") @PathVariable String policyNumber,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.of(service.history(policyNumber, CallerEntitlements.fromJwt(jwt)));
  }

  private static ResponseEntity<UnderwritingDecisionView> created(
      Optional<UnderwritingDecisionView> decision, String correlationId) {
    return decision
        .map(
            d ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .header(CorrelationIds.HEADER, correlationId)
                    .body(d))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
