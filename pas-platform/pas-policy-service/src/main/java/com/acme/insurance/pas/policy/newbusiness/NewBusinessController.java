package com.acme.insurance.pas.policy.newbusiness;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import com.acme.insurance.pas.policy.events.CorrelationIds;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.platform.ApiProblem;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@SecurityRequirement(name = "bearerAuth")
public class NewBusinessController {
  private final NewBusinessService service;
  private final RouteFlags routeFlags;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are Spring-managed application services.")
  public NewBusinessController(NewBusinessService service, RouteFlags routeFlags) {
    this.service = service;
    this.routeFlags = routeFlags;
  }

  @PostMapping
  @Operation(
      summary = "Create a new-business policy",
      description =
          "Requires policy:write and entitlement to the requested agent or branch. Shadow mode records only PII-free validation results.",
      parameters =
          @Parameter(in = ParameterIn.HEADER, name = CorrelationIds.HEADER_NAME, required = false))
  public ResponseEntity<Object> create(
      @RequestBody NewBusinessRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestAttribute(CorrelationIds.REQUEST_ATTRIBUTE) String correlationId,
      @AuthenticationPrincipal Jwt jwt) {
    RouteState state = routeFlags.state(RouteFlags.NEW_BUSINESS);
    if (state == RouteState.LEGACY || state == RouteState.RETIRED) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(
              ApiProblem.of(
                  HttpStatus.SERVICE_UNAVAILABLE.value(),
                  HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                  "New-business route is not serving traffic"));
    }
    CreatePolicyResult result =
        service.create(
            request,
            idempotencyKey,
            jwt.getSubject(),
            CallerEntitlements.from(jwt),
            state == RouteState.SHADOW,
            correlationId);
    HttpHeaders headers = new HttpHeaders();
    if (result.location() != null) {
      headers.set(HttpHeaders.LOCATION, result.location());
    }
    if (result.problem()) {
      headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }
    return new ResponseEntity<>(result.body(), headers, HttpStatus.valueOf(result.status()));
  }
}
