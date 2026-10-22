package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.security.PasScopes;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.pii.Masking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@SecurityRequirement(name = "bearerAuth")
public class PolicyInquiryController {
  private final PolicyInquiryRepository repository;
  private final RouteFlags routeFlags;

  public PolicyInquiryController(PolicyInquiryRepository repository, RouteFlags routeFlags) {
    this.repository = repository;
    this.routeFlags = routeFlags;
  }

  @GetMapping("/{policyNumber}")
  @Operation(
      summary = "Read a policy",
      description =
          "Requires policy:read and a matching book, agent_id, or branch_codes entitlement.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Policy found"),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid bearer token",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "403",
        description = "Token lacks policy:read",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "404",
        description = "Policy is unknown or not entitled",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "503",
        description = "Policy inquiry route is not serving traffic",
        content = @Content(schema = @Schema(hidden = true)))
  })
  public ResponseEntity<PolicyResponse> policy(
      @PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
    if (!routeFlags.servesTraffic(RouteFlags.POLICY_INQUIRY)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    Optional<Policy> policy = repository.findPolicy(policyNumber, CallerEntitlements.from(jwt));
    return policy
        .map(value -> ResponseEntity.ok(policyResponse(value)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/{policyNumber}/coverages")
  @Operation(
      summary = "Read all coverages for a policy",
      description =
          "Requires policy:read and the policy's book, agent_id, or branch_codes entitlement.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Policy coverages in sequence order"),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid bearer token",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "403",
        description = "Token lacks policy:read",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "404",
        description = "Policy is unknown or not entitled",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "503",
        description = "Policy inquiry route is not serving traffic",
        content = @Content(schema = @Schema(hidden = true)))
  })
  public ResponseEntity<List<CoverageResponse>> coverages(
      @PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
    if (!routeFlags.servesTraffic(RouteFlags.POLICY_INQUIRY)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    CallerEntitlements entitlements = CallerEntitlements.from(jwt);
    if (repository.findPolicy(policyNumber, entitlements).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    List<CoverageResponse> responses =
        repository.findCoverages(policyNumber, entitlements).stream()
            .map(PolicyInquiryController::coverageResponse)
            .toList();
    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{policyNumber}/policyholder")
  @Operation(
      summary = "Read the policyholder screen view",
      description =
          "Requires policy:read and policy entitlement. pii:read reveals regulated fields; otherwise values are masked.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Policyholder screen view"),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid bearer token",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "403",
        description = "Token lacks policy:read",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "404",
        description = "Policy or policyholder is unknown or not entitled",
        content = @Content(schema = @Schema(hidden = true))),
    @ApiResponse(
        responseCode = "503",
        description = "Policy inquiry route is not serving traffic",
        content = @Content(schema = @Schema(hidden = true)))
  })
  public ResponseEntity<PolicyholderResponse> policyholder(
      @PathVariable String policyNumber,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    if (!routeFlags.servesTraffic(RouteFlags.POLICY_INQUIRY)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .headers(responseHeaders -> responseHeaders.putAll(headers))
          .build();
    }
    Optional<PolicyHolder> holder =
        repository.findHolder(policyNumber, CallerEntitlements.from(jwt));
    if (holder.isEmpty()) {
      return ResponseEntity.notFound()
          .headers(responseHeaders -> responseHeaders.putAll(headers))
          .build();
    }
    boolean piiRead =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(PasScopes.authority(PasScopes.PII_READ)::equals);
    return ResponseEntity.ok()
        .headers(responseHeaders -> responseHeaders.putAll(headers))
        .body(policyholderResponse(policyNumber, holder.get(), piiRead));
  }

  private static PolicyResponse policyResponse(Policy policy) {
    return new PolicyResponse(
        policy.policyNumber(),
        raw(policy.policyType()),
        raw(policy.policyStatus()),
        date(policy.effectiveDate()),
        date(policy.expiryDate()),
        policy.policyholderId(),
        policy.agentCode(),
        policy.branchCode(),
        policy.totalPremium(),
        policy.deductible(),
        policy.coverageLimit(),
        date(policy.inceptionDate()),
        policy.renewalCount() == null ? 0 : policy.renewalCount(),
        raw(policy.uwStatus()),
        policy.riskScore() == null ? 0 : policy.riskScore(),
        raw(policy.webIndicator()),
        raw(policy.apiFlag()),
        policy.lastUpdated().toInstant().toEpochMilli(),
        policy.updatedBy());
  }

  private static CoverageResponse coverageResponse(Coverage coverage) {
    return new CoverageResponse(
        coverage.policyNumber(),
        coverage.sequenceNum() == null ? 0 : coverage.sequenceNum(),
        raw(coverage.coverageType()),
        coverage.description(),
        coverage.coverageLimit(),
        coverage.deductible(),
        coverage.premium(),
        date(coverage.effectiveDate()),
        date(coverage.expiryDate()),
        raw(coverage.status()),
        coverage.coinsurancePct() == null ? 0 : coverage.coinsurancePct(),
        coverage.ratingTerritory(),
        coverage.classCode());
  }

  private static PolicyholderResponse policyholderResponse(
      String policyNumber, PolicyHolder holder, boolean piiRead) {
    LocalDate dateOfBirth = holder.dateOfBirth();
    return new PolicyholderResponse(
        policyNumber,
        holder.custId(),
        raw(holder.custType()),
        holder.lastName(),
        holder.firstName(),
        holder.middleInit(),
        holder.companyName(),
        holder.addrLine1(),
        holder.addrLine2(),
        holder.city(),
        holder.stateCode(),
        holder.zipCode(),
        holder.countryCode(),
        holder.phone(),
        holder.email(),
        piiRead ? date(dateOfBirth) : null,
        dateOfBirth == null ? null : dateOfBirth.getYear(),
        piiRead ? holder.ssnLast4() : Masking.redact(holder.ssnLast4()),
        piiRead ? holder.taxId() : maskTaxId(holder.taxId()),
        piiRead ? holder.creditScore() : null,
        raw(holder.riskTier()),
        raw(holder.gdprConsent()),
        !piiRead);
  }

  private static String maskTaxId(String taxId) {
    if (taxId == null) {
      return null;
    }
    return taxId.length() <= 4 ? Masking.redact(taxId) : Masking.last(taxId, 4);
  }

  private static String date(LocalDate date) {
    return date == null ? null : date.toString();
  }

  private static String raw(com.acme.insurance.pas.domain.Coded<?> value) {
    return value == null ? null : value.raw();
  }
}
