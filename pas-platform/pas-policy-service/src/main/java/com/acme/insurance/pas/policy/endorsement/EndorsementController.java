package com.acme.insurance.pas.policy.endorsement;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@SecurityRequirement(name = "bearerAuth")
public class EndorsementController {
  private final EndorsementService service;
  private final RouteFlags routeFlags;

  public EndorsementController(EndorsementService service, RouteFlags routeFlags) {
    this.service = service;
    this.routeFlags = routeFlags;
  }

  @PostMapping("/{policyNumber}/endorsements")
  @Operation(
      summary = "Endorse a policy mid-term (POLEND)",
      description =
          "Requires policy:endorse and a matching book, agent_id, or branch_codes entitlement."
              + " Concurrent endorsements on one policy are serialised by a row lock.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Endorsement recorded and applied"),
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
        description = "Token lacks policy:endorse",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "404",
        description = "Policy is unknown or not entitled",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description = "Endorsement rejected: type, term, date or details invalid",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Endorsement route is LEGACY",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  public ResponseEntity<EndorsementResponse> endorse(
      @PathVariable String policyNumber,
      @Valid @RequestBody EndorsementRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    RouteState state = routeFlags.state(RouteFlags.ENDORSEMENT);
    CallerEntitlements caller = CallerEntitlements.from(jwt);
    return switch (state) {
      case LEGACY -> throw new RouteNotServingException(RouteFlags.ENDORSEMENT);
      case SHADOW ->
          ResponseEntity.status(HttpStatus.ACCEPTED)
              .body(service.shadow(policyNumber, request, caller));
      case NEW, RETIRED ->
          ResponseEntity.status(HttpStatus.CREATED)
              .body(service.endorse(policyNumber, request, caller, jwt.getSubject()));
    };
  }
}
