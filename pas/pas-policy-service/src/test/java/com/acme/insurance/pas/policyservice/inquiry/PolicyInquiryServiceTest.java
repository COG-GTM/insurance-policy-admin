package com.acme.insurance.pas.policyservice.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyHolderContact;
import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.inquiry.InquiryResult.Outcome;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyInquiryServiceTest {

  private static final Policy POLICY =
      new Policy(
          "POL-00000001",
          "HOM",
          "AC",
          null,
          null,
          "C000000001",
          "AG1001",
          "CHI1",
          null,
          null,
          null,
          null,
          null,
          "AP",
          null,
          "Y",
          "N",
          LocalDateTime.of(2026, 1, 1, 0, 0),
          "TNGUYEN");

  private final PolicyJdbcRepository policies = mock(PolicyJdbcRepository.class);
  private final PolicyHolderJdbcRepository holders = mock(PolicyHolderJdbcRepository.class);
  private final PolicyInquiryService service = new PolicyInquiryService(policies, holders);

  @Test
  void entitledCallerGetsThePolicyEvenWhenApiFlagIsN() {
    when(policies.findPolicy("POL-00000001")).thenReturn(Optional.of(POLICY));
    InquiryResult<Policy> result =
        service.policy("POL-00000001", new Entitlement(false, Set.of("AG1001"), Set.of()));
    assertThat(result.outcome()).isEqualTo(Outcome.FOUND);
    assertThat(result.value()).isSameAs(POLICY);
  }

  @Test
  void otherTenantIsDeniedAndReadsNoCoveragesOrHolder() {
    when(policies.findPolicy("POL-00000001")).thenReturn(Optional.of(POLICY));
    Entitlement other = new Entitlement(false, Set.of("AG9999"), Set.of("DET1"));
    assertThat(service.policy("POL-00000001", other).outcome()).isEqualTo(Outcome.DENIED);
    assertThat(service.coverages("POL-00000001", other).outcome()).isEqualTo(Outcome.DENIED);
    assertThat(service.policyHolder("POL-00000001", other, true).outcome())
        .isEqualTo(Outcome.DENIED);
    verify(policies, never()).findCoverages(any());
    verify(holders, never()).findContact(any());
    verify(holders, never()).findMaskedContact(any());
  }

  @Test
  void overlongPolicyNumbersAreNotLookedUp() {
    assertThat(service.policy("POL-0000000012", Entitlement.NONE).outcome())
        .isEqualTo(Outcome.NOT_FOUND);
    verify(policies, never()).findPolicy(any());
  }

  @Test
  void holderIsMaskedUnlessClearDataIsAllowed() {
    Entitlement book = new Entitlement(true, Set.of(), Set.of());
    when(policies.findPolicy("POL-00000001")).thenReturn(Optional.of(POLICY));
    PolicyHolderContact masked =
        new PolicyHolderContact("C000000001", "I", "S***", "J***", null, null, "***@x", true);
    when(holders.findMaskedContact("C000000001")).thenReturn(Optional.of(masked));

    assertThat(service.policyHolder("POL-00000001", book, false).value()).isSameAs(masked);
    verify(holders, never()).findContact(any());
  }

  @Test
  void viewsKeepTheFacadeSerialisation() {
    PolicyView view = PolicyView.from(POLICY, ZoneId.of("UTC"));
    assertThat(view.renewalCount()).isZero();
    assertThat(view.riskScore()).isZero();
    assertThat(view.lastUpdated()).isEqualTo(1767225600000L);
    assertThat(view.policyType()).isEqualTo("HOM");
  }
}
