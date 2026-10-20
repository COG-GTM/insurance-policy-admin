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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Renewals v1: replaces CICS PRWL (POLRNW). */
@RestController
@RequestMapping(
    path = "/api/v1/policies/{policyNumber}/renewals",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Renewals", description = "Policy renewal (replaces POLRNW)")
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
public class RenewalController {

  private final RenewalService service;

  public RenewalController(RenewalService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      operationId = "renewPolicy",
      summary = "Renew a policy for one more year",
      description =
          "POLRNW at parity except the approved fixes: AC/EX policies only, more open claims than"
              + " pas.renewal.max-open-claims is ineligible (Q-02), premium x (1 + uplift) truncated"
              + " and capped at pas.renewal.rate-cap-pct, new term starts at the old expiry and ends"
              + " one calendar year later (Feb 29 renews to Feb 28, Q-01), renewal count + 1,"
              + " status AC / underwriting PN, active coverages re-dated. Publishes PolicyRenewed.")
  @ApiResponse(responseCode = "201", description = "Policy renewed")
  @ApiResponse(
      responseCode = "422",
      description = "Not eligible for renewal (status or open claims) or term cannot be renewed",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @ApiResponse(
      responseCode = "503",
      description = "Claims history unavailable",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ResponseEntity<RenewalView> renewPolicy(
      @Parameter(example = "POL001000000") @PathVariable String policyNumber,
      @Parameter(description = "Propagated to events as correlationId")
          @RequestHeader(name = CorrelationIds.HEADER, required = false)
          String correlationHeader,
      @AuthenticationPrincipal Jwt jwt) {
    String correlationId = CorrelationIds.resolve(correlationHeader);
    Optional<RenewalView> renewed =
        service.renew(
            policyNumber,
            CallerEntitlements.fromJwt(jwt),
            JwtSubjects.checkLength(Objects.requireNonNullElse(jwt.getSubject(), "")),
            correlationId);
    return LifecycleResponses.created(renewed, correlationId);
  }
}
