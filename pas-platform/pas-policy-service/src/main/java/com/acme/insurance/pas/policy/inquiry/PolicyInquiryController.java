package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.security.PasScopes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy inquiry v1: replaces CICS PQRY (POLQRY) and the {@code java-facade} read endpoints. Not
 * found and not entitled both return 404 with an empty body, matching the facade's 404 shape.
 */
@RestController
@RequestMapping(path = "/api/v1/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Policy inquiry", description = "Read-only policy inquiry (replaces POLQRY)")
@SecurityRequirement(name = "bearer-jwt", scopes = PasScopes.POLICY_READ)
@ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
@ApiResponse(responseCode = "403", description = "Token lacks scope policy:read")
public class PolicyInquiryController {

  private final PolicyInquiryService service;

  public PolicyInquiryController(PolicyInquiryService service) {
    this.service = service;
  }

  @GetMapping("/{policyNumber}")
  @Operation(operationId = "getPolicy", summary = "Get a policy")
  @ApiResponse(responseCode = "200", description = "Policy")
  @ApiResponse(
      responseCode = "404",
      description = "Not found or not entitled",
      content = @Content(schema = @io.swagger.v3.oas.annotations.media.Schema(hidden = true)))
  public ResponseEntity<PolicyView> getPolicy(
      @Parameter(example = "POL-00000001") @PathVariable String policyNumber,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.of(service.policy(policyNumber, CallerEntitlements.fromJwt(jwt)));
  }

  @GetMapping("/{policyNumber}/coverages")
  @Operation(operationId = "getCoverages", summary = "List a policy's coverages")
  @ApiResponse(responseCode = "200", description = "Coverages ordered by sequenceNum")
  @ApiResponse(
      responseCode = "404",
      description = "Not found or not entitled",
      content = @Content(schema = @io.swagger.v3.oas.annotations.media.Schema(hidden = true)))
  public ResponseEntity<List<CoverageView>> getCoverages(
      @Parameter(example = "POL-00000001") @PathVariable String policyNumber,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.of(service.coverages(policyNumber, CallerEntitlements.fromJwt(jwt)));
  }

  @GetMapping("/{policyNumber}/holder")
  @Operation(
      operationId = "getPolicyHolder",
      summary = "Get the policyholder",
      description = "PII is masked unless the token also carries scope policy:pii:read.")
  @ApiResponse(responseCode = "200", description = "Policyholder (masked unless policy:pii:read)")
  @ApiResponse(
      responseCode = "404",
      description = "Not found or not entitled",
      content = @Content(schema = @io.swagger.v3.oas.annotations.media.Schema(hidden = true)))
  public ResponseEntity<PolicyHolderView> getPolicyHolder(
      @Parameter(example = "POL-00000001") @PathVariable String policyNumber,
      @AuthenticationPrincipal Jwt jwt) {
    boolean pii = PasScopes.hasScope(jwt, PasScopes.POLICY_PII_READ);
    return ResponseEntity.of(service.holder(policyNumber, CallerEntitlements.fromJwt(jwt), pii));
  }
}
