package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.common.error.ApiError;
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
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endorsements v1: replaces CICS PEND (POLEND). */
@RestController
@RequestMapping(
    path = "/api/v1/policies/{policyNumber}/endorsements",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Endorsements", description = "Mid-term endorsements (replaces POLEND)")
@SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.POLICY_WRITE)
@ApiResponse(
    responseCode = "401",
    description = "Missing or invalid bearer token",
    content = @Content(schema = @Schema(hidden = true)))
@ApiResponse(
    responseCode = "403",
    description = "Token lacks scope policy:write",
    content = @Content(schema = @Schema(hidden = true)))
@ApiResponse(
    responseCode = "404",
    description = "Not found or not entitled",
    content = @Content(schema = @Schema(hidden = true)))
@ApiResponse(
    responseCode = "409",
    description = "Concurrent update of the policy",
    content = @Content(schema = @Schema(implementation = ApiError.class)))
public class EndorsementController {

  private final EndorsementService service;

  public EndorsementController(EndorsementService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "endorsePolicy",
      summary = "Record a mid-term endorsement",
      description =
          "POLEND at parity: active policies only, type required (422 with the COBOL message),"
              + " pro-rata factor = days remaining / days in term (1 for a zero-day term), next"
              + " ENDORSEMENTS sequence, premium adjustment applied to the policy (zero at parity,"
              + " Q-11), audit row and PolicyEndorsed. Concurrent endorsements of one policy are"
              + " serialised by a row lock.")
  @ApiResponse(responseCode = "201", description = "Endorsement recorded")
  @ApiResponse(
      responseCode = "400",
      description = "Malformed request or unknown endorsement type",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "422",
      description = "POLEND validation failed (inactive policy or blank type)",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ResponseEntity<EndorsementView> endorsePolicy(
      @Parameter(example = "POL001000000") @PathVariable String policyNumber,
      @RequestBody EndorsementRequest request,
      @Parameter(description = "Propagated to events as correlationId")
          @RequestHeader(name = CorrelationIds.HEADER, required = false)
          String correlationHeader,
      @AuthenticationPrincipal Jwt jwt) {
    String correlationId = CorrelationIds.resolve(correlationHeader);
    Optional<EndorsementView> endorsed =
        service.endorse(
            policyNumber,
            request,
            CallerEntitlements.fromJwt(jwt),
            JwtSubjects.checkLength(Objects.requireNonNullElse(jwt.getSubject(), "")),
            correlationId);
    return LifecycleResponses.created(endorsed, correlationId);
  }
}
