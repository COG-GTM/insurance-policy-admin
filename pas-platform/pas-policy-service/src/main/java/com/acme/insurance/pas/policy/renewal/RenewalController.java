package com.acme.insurance.pas.policy.renewal;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import com.acme.insurance.pas.policy.events.CorrelationIds;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.lifecycle.RouteNotServingException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@SecurityRequirement(name = "bearerAuth")
public class RenewalController {
  private final RenewalService service;
  private final RouteFlags routeFlags;

  public RenewalController(RenewalService service, RouteFlags routeFlags) {
    this.service = service;
    this.routeFlags = routeFlags;
  }

  @PostMapping("/{policyNumber}/renewals")
  @Operation(
      summary = "Renew a policy for the next term (POLRNW)",
      description =
          "Requires policy:renew and a matching book, agent_id, or branch_codes entitlement."
              + " Idempotent per term: a second request for the same termExpiryDate is 409.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Policy renewed"),
    @ApiResponse(
        responseCode = "202",
        description = "Route is in SHADOW: result recorded in pas_shadow, policy unchanged"),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed request",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid bearer token",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "403",
        description = "Token lacks policy:renew",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "404",
        description = "Policy is unknown or not entitled",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Term already renewed or termExpiryDate is not the current term",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description = "Renewal rejected by a business rule",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Renewal route is LEGACY or the claims source is unavailable",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public ResponseEntity<RenewalResponse> renew(
      @PathVariable String policyNumber,
      @Valid @RequestBody RenewalRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute(CorrelationIds.REQUEST_ATTRIBUTE) String correlationId) {
    RouteState state = routeFlags.state(RouteFlags.RENEWAL);
    CallerEntitlements caller = CallerEntitlements.from(jwt);
    return switch (state) {
      case LEGACY -> throw new RouteNotServingException(RouteFlags.RENEWAL);
      case SHADOW ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(service.shadow(policyNumber, request, caller));
      case NEW, RETIRED ->
          ResponseEntity.status(HttpStatus.CREATED)
              .body(service.renew(policyNumber, request, caller, jwt.getSubject(), correlationId));
    };
  }
}
