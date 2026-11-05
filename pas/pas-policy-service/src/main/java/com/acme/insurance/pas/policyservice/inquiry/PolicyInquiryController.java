package com.acme.insurance.pas.policyservice.inquiry;

import static com.acme.insurance.pas.policyservice.security.PasScopes.POLICYHOLDER_READ;
import static com.acme.insurance.pas.policyservice.security.PasScopes.authority;

import com.acme.insurance.pas.policyservice.security.EntitlementResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The policy inquiry API (OpenAPI: classpath:openapi/policy-inquiry-v1.yaml). The two facade
 * endpoints keep their paths and JSON; a policy the caller is not entitled to answers 404 with an
 * empty body, exactly like a policy that does not exist. Requests are counted per calling client so
 * facade-to-API consumer migration can be tracked (docs/pas/facade-retirement.md).
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyInquiryController {

  static final String METRIC = "pas.inquiry.requests";
  private static final Logger log = LoggerFactory.getLogger(PolicyInquiryController.class);
  private static final Pattern SAFE_CLIENT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  private final PolicyInquiryService inquiry;
  private final EntitlementResolver entitlements;
  private final MeterRegistry meters;
  private final ZoneId timestampZone;

  public PolicyInquiryController(
      PolicyInquiryService inquiry,
      EntitlementResolver entitlements,
      MeterRegistry meters,
      @Value("${pas.inquiry.timestamp-zone:UTC}") ZoneId timestampZone) {
    this.inquiry = inquiry;
    this.entitlements = entitlements;
    this.meters = meters;
    this.timestampZone = timestampZone;
  }

  @GetMapping("/{policyNumber}")
  public ResponseEntity<PolicyView> policy(
      @PathVariable String policyNumber, Authentication caller) {
    return respond(
        "policy",
        caller,
        policyNumber,
        inquiry.policy(policyNumber, entitlements.resolve(caller)),
        p -> PolicyView.from(p, timestampZone));
  }

  @GetMapping("/{policyNumber}/coverages")
  public ResponseEntity<List<CoverageView>> coverages(
      @PathVariable String policyNumber, Authentication caller) {
    return respond(
        "coverages",
        caller,
        policyNumber,
        inquiry.coverages(policyNumber, entitlements.resolve(caller)),
        list -> list.stream().map(CoverageView::from).toList());
  }

  @GetMapping("/{policyNumber}/policyholder")
  public ResponseEntity<PolicyHolderView> policyHolder(
      @PathVariable String policyNumber, Authentication caller) {
    boolean clear =
        caller.getAuthorities().stream()
            .anyMatch(a -> authority(POLICYHOLDER_READ).equals(a.getAuthority()));
    return respond(
        "policyholder",
        caller,
        policyNumber,
        inquiry.policyHolder(policyNumber, entitlements.resolve(caller), clear),
        PolicyHolderView::from);
  }

  private <T, V> ResponseEntity<V> respond(
      String operation,
      Authentication caller,
      String policyNumber,
      InquiryResult<T> result,
      Function<T, V> view) {
    String client = clientId(caller);
    Counter.builder(METRIC)
        .description("Policy inquiry requests by operation, outcome and calling client")
        .tag("operation", operation)
        .tag("outcome", result.outcome().name().toLowerCase(java.util.Locale.ROOT))
        .tag("client", client)
        .register(meters)
        .increment();
    if (result.outcome() == InquiryResult.Outcome.DENIED) {
      log.info(
          "policy inquiry denied by entitlement: operation={} client={} policyNumber={}",
          operation,
          client,
          policyNumber);
    }
    return result.outcome() == InquiryResult.Outcome.FOUND
        ? ResponseEntity.ok(view.apply(result.value()))
        : ResponseEntity.notFound().build();
  }

  /** The OAuth client the token was issued to; bounded so it is safe as a metric tag. */
  static String clientId(Authentication caller) {
    if (caller instanceof JwtAuthenticationToken jwt) {
      for (String claim : new String[] {"azp", "client_id"}) {
        Object value = jwt.getToken().getClaims().get(claim);
        if (value instanceof String s) {
          return SAFE_CLIENT.matcher(s).matches() ? s : "invalid";
        }
      }
    }
    return "unknown";
  }
}
