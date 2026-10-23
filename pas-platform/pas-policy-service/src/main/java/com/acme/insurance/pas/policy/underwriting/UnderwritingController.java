package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import com.acme.insurance.pas.policy.events.CorrelationIds;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.platform.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies/{policyNumber}/underwriting-decisions")
@SecurityRequirement(name = "bearerAuth")
public class UnderwritingController {
  private final UnderwritingService service;
  private final RouteFlags routeFlags;

  public UnderwritingController(UnderwritingService service, RouteFlags routeFlags) {
    this.service = service;
    this.routeFlags = routeFlags;
  }

  @PostMapping
  @Operation(
      summary = "Run underwriting for a policy",
      parameters =
          @Parameter(in = ParameterIn.HEADER, name = CorrelationIds.HEADER_NAME, required = false))
  public ResponseEntity<Object> decide(
      @PathVariable String policyNumber,
      @RequestAttribute(CorrelationIds.REQUEST_ATTRIBUTE) String correlationId,
      @AuthenticationPrincipal Jwt jwt) {
    policyNumber = UnderwritingService.normalizePolicyNumber(policyNumber);
    if (!UnderwritingService.validPolicyNumber(policyNumber)) {
      return problem(
          HttpStatus.BAD_REQUEST, "Policy number must be 1 to 12 letters, digits, or hyphens");
    }
    RouteState state = routeFlags.state(RouteFlags.UNDERWRITING);
    if (state == RouteState.LEGACY || state == RouteState.RETIRED) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "Underwriting route is not serving traffic");
    }
    DecisionResponse response =
        service.decide(
            policyNumber,
            CallerEntitlements.from(jwt),
            jwt.getSubject(),
            correlationId,
            state == RouteState.SHADOW);
    return ResponseEntity.status(state == RouteState.SHADOW ? HttpStatus.OK : HttpStatus.CREATED)
        .body(response);
  }

  @PostMapping("/{decisionId}/override")
  @Operation(
      summary = "Override the latest underwriting decision",
      parameters =
          @Parameter(in = ParameterIn.HEADER, name = CorrelationIds.HEADER_NAME, required = false))
  public ResponseEntity<Object> override(
      @PathVariable String policyNumber,
      @PathVariable long decisionId,
      @RequestBody OverrideRequest request,
      @RequestAttribute(CorrelationIds.REQUEST_ATTRIBUTE) String correlationId,
      @AuthenticationPrincipal Jwt jwt) {
    policyNumber = UnderwritingService.normalizePolicyNumber(policyNumber);
    if (!UnderwritingService.validPolicyNumber(policyNumber)) {
      return problem(
          HttpStatus.BAD_REQUEST, "Policy number must be 1 to 12 letters, digits, or hyphens");
    }
    RouteState state = routeFlags.state(RouteFlags.UNDERWRITING);
    if (state != RouteState.NEW) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE, "Underwriting override route is not serving traffic");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            service.override(
                policyNumber,
                decisionId,
                CallerEntitlements.from(jwt),
                jwt.getSubject(),
                correlationId,
                request));
  }

  @GetMapping
  @Operation(summary = "List underwriting decisions for a policy")
  public ResponseEntity<Object> list(
      @PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
    policyNumber = UnderwritingService.normalizePolicyNumber(policyNumber);
    if (!UnderwritingService.validPolicyNumber(policyNumber)) {
      return problem(
          HttpStatus.BAD_REQUEST, "Policy number must be 1 to 12 letters, digits, or hyphens");
    }
    RouteState state = routeFlags.state(RouteFlags.UNDERWRITING);
    if (state == RouteState.LEGACY || state == RouteState.RETIRED) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "Underwriting route is not serving traffic");
    }
    List<DecisionResponse> decisions = service.list(policyNumber, CallerEntitlements.from(jwt));
    return ResponseEntity.ok(decisions);
  }

  private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ApiProblem.of(status.value(), status.getReasonPhrase(), detail));
  }
}
