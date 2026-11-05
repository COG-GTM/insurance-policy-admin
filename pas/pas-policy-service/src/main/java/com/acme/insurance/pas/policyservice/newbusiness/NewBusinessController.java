package com.acme.insurance.pas.policyservice.newbusiness;

import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.policyservice.observability.CorrelationIdFilter;
import com.acme.insurance.pas.policyservice.security.CallerIdentity;
import com.acme.insurance.pas.policyservice.security.EntitlementResolver;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/policies} (OpenAPI: classpath:openapi/new-business-underwriting-v1.yaml).
 * Needs {@code pas.policy.write} and an entitlement covering the new policy's agent or branch.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class NewBusinessController {

  private static final Logger LOG = LoggerFactory.getLogger(NewBusinessController.class);

  private final NewBusinessService service;
  private final EntitlementResolver entitlements;

  public NewBusinessController(NewBusinessService service, EntitlementResolver entitlements) {
    this.service = service;
    this.entitlements = entitlements;
  }

  /** Response body: the stored policy header and its default coverages. */
  public record CreatedPolicy(
      String policyNumber,
      String policyType,
      String policyStatus,
      String uwStatus,
      LocalDate effectiveDate,
      LocalDate expiryDate,
      String policyholderId,
      String agentCode,
      String branchCode,
      BigDecimal totalPremium,
      BigDecimal deductible,
      BigDecimal coverageLimit,
      List<CoverageLine> coverages) {

    public CreatedPolicy {
      coverages = List.copyOf(coverages);
    }

    static CreatedPolicy of(Policy p, List<Coverage> coverages) {
      return new CreatedPolicy(
          p.policyNumber(),
          p.policyTypeCode(),
          p.policyStatusCode(),
          p.uwStatusCode(),
          p.effectiveDate(),
          p.expiryDate(),
          p.policyholderId(),
          p.agentCode(),
          p.branchCode(),
          p.totalPremium(),
          p.deductible(),
          p.coverageLimit(),
          coverages.stream()
              .map(c -> new CoverageLine(c.sequenceNum(), c.coverageTypeCode(), c.description()))
              .toList());
    }
  }

  /** One default coverage. */
  public record CoverageLine(int sequenceNum, String coverageType, String description) {}

  @PostMapping
  public ResponseEntity<?> create(@RequestBody NewPolicyRequest request, Authentication caller) {
    CallerIdentity identity = CallerIdentity.of(caller);
    NewPolicyResult result =
        service.create(
            request, entitlements.resolve(caller), identity, MDC.get(CorrelationIdFilter.MDC_KEY));
    return switch (result) {
      case NewPolicyResult.Created c -> {
        LOG.info(
            "policy created: policyNumber={} client={}",
            c.policy().policyNumber(),
            identity.clientId());
        yield ResponseEntity.created(URI.create("/api/v1/policies/" + c.policy().policyNumber()))
            .body(CreatedPolicy.of(c.policy(), c.coverages()));
      }
      case NewPolicyResult.Malformed m -> ResponseEntity.badRequest().body(m.error());
      case NewPolicyResult.Invalid i ->
          ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(i.error());
      case NewPolicyResult.Forbidden f -> {
        LOG.info("policy create denied by entitlement: client={}", identity.clientId());
        yield ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
    };
  }
}
