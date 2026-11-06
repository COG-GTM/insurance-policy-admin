package com.acme.insurance.pas.policyservice.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.events.PasEvent;
import com.acme.insurance.pas.events.PasEventType;
import com.acme.insurance.pas.events.PolicyEndorsed;
import com.acme.insurance.pas.events.PolicyRenewed;
import com.acme.insurance.pas.persistence.EndorsementJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyChangeAuditJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.change.EndorsementService.EndorsementCommand;
import com.acme.insurance.pas.policyservice.change.PolicyChangeConfiguration.OpenClaimsRule;
import com.acme.insurance.pas.policyservice.change.PolicyChangeConfiguration.PolicyChangeClock;
import com.acme.insurance.pas.policyservice.events.OutboxWriter;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import com.acme.insurance.pas.rating.PolicyChangeError;
import com.acme.insurance.pas.rating.RenewalCalculator;
import com.acme.insurance.pas.rating.RenewalRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Service rules that need no database: the premium-change flag and the one-row update check. */
class PolicyChangeServicesTest {

  private static final String NUMBER = "POL-00000001";
  private static final Entitlement WHOLE_BOOK =
      new Entitlement(true, java.util.Set.of(), java.util.Set.of());
  private static final Actor ACTOR = new Actor("test-user", "client", "corr-0001");
  private static final PolicyChangeClock CLOCK =
      new PolicyChangeClock(Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC));

  private final PolicyJdbcRepository policies = mock(PolicyJdbcRepository.class);
  private final EndorsementJdbcRepository endorsements = mock(EndorsementJdbcRepository.class);
  private final PolicyChangeAuditJdbcRepository audit = mock(PolicyChangeAuditJdbcRepository.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);

  private static PolicyChangeProperties properties(boolean premiumChangeEnabled) {
    return new PolicyChangeProperties(
        new BigDecimal("5.00"),
        new BigDecimal("15.00"),
        RenewalRules.LeapDayExpiry.FEB_28,
        null,
        ZoneOffset.UTC,
        premiumChangeEnabled);
  }

  private static Policy policy() {
    return new Policy(
        NUMBER,
        "AUT",
        "AC",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2027, 1, 1),
        "C000000001",
        "AG1001",
        "CHI1",
        new BigDecimal("100.00"),
        null,
        null,
        null,
        0,
        "AP",
        null,
        "Y",
        "N",
        null,
        null);
  }

  private void lockable() {
    when(policies.findPolicy(NUMBER)).thenReturn(Optional.of(policy()));
    when(policies.findPolicyForUpdate(NUMBER)).thenReturn(Optional.of(policy()));
  }

  private EndorsementService endorsementService(boolean premiumChangeEnabled) {
    return new EndorsementService(
        policies, endorsements, audit, CLOCK, properties(premiumChangeEnabled), outbox);
  }

  @Test
  void nonZeroPremiumChangeIsRefusedWhileTheFlagIsOff() {
    ChangeResult<EndorsementService.EndorsementOutcome> result =
        endorsementService(false)
            .endorse(
                NUMBER,
                new EndorsementCommand("CAD", null, new BigDecimal("10.00")),
                WHOLE_BOOK,
                ACTOR);

    assertThat(result.outcome()).isEqualTo(ChangeResult.Outcome.REJECTED);
    assertThat(result.error()).isEqualTo(PolicyChangeError.ENDORSEMENT_PREMIUM_CHANGE_DISABLED);
    verifyNoInteractions(policies, endorsements, audit);
  }

  @Test
  void zeroOrAbsentPremiumChangeIsAllowedWhileTheFlagIsOff() {
    lockable();
    when(endorsements.nextSequence(NUMBER)).thenReturn(1);
    when(policies.updateTotalPremium(eq(NUMBER), any(), any(), anyString())).thenReturn(1);
    EndorsementService service = endorsementService(false);

    for (BigDecimal change : new BigDecimal[] {null, new BigDecimal("0.00")}) {
      assertThat(
              service
                  .endorse(NUMBER, new EndorsementCommand("CAD", null, change), WHOLE_BOOK, ACTOR)
                  .outcome())
          .isEqualTo(ChangeResult.Outcome.DONE);
    }
  }

  @Test
  void nonZeroPremiumChangeIsAppliedWhenTheFlagIsOn() {
    lockable();
    when(endorsements.nextSequence(NUMBER)).thenReturn(1);
    when(policies.updateTotalPremium(eq(NUMBER), any(), any(), anyString())).thenReturn(1);

    ChangeResult<EndorsementService.EndorsementOutcome> result =
        endorsementService(true)
            .endorse(
                NUMBER,
                new EndorsementCommand("CAD", null, new BigDecimal("10.00")),
                WHOLE_BOOK,
                ACTOR);

    assertThat(result.outcome()).isEqualTo(ChangeResult.Outcome.DONE);
    assertThat(result.value().totalPremium()).isGreaterThan(new BigDecimal("100.00"));
    ArgumentCaptor<PasEvent> event = ArgumentCaptor.forClass(PasEvent.class);
    verify(outbox).append(event.capture());
    assertThat(event.getValue().type()).isEqualTo(PasEventType.POLICY_ENDORSED);
    assertThat(event.getValue().policyNumber()).isEqualTo(NUMBER);
    PolicyEndorsed data = (PolicyEndorsed) event.getValue().data();
    assertThat(data.endorsementSeq()).isEqualTo(1);
    assertThat(data.totalPremium()).isEqualTo(result.value().totalPremium());
  }

  @Test
  void aRenewalWritesThePolicyRenewedEventInTheSameTransaction() {
    lockable();
    when(policies.updateRenewal(any())).thenReturn(1);

    ChangeResult<RenewalService.Renewal> result =
        new RenewalService(
                policies,
                audit,
                new RenewalCalculator(properties(false).renewalRules()),
                OpenClaimsRule.NOT_ENFORCED,
                CLOCK,
                outbox)
            .renew(NUMBER, null, WHOLE_BOOK, ACTOR);

    assertThat(result.outcome()).isEqualTo(ChangeResult.Outcome.DONE);
    ArgumentCaptor<PasEvent> event = ArgumentCaptor.forClass(PasEvent.class);
    verify(outbox).append(event.capture());
    assertThat(event.getValue().type()).isEqualTo(PasEventType.POLICY_RENEWED);
    PolicyRenewed data = (PolicyRenewed) event.getValue().data();
    assertThat(data.previousExpiryDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(data.expiryDate()).isAfter(data.previousExpiryDate());
    assertThat(data.renewalCount()).isEqualTo(1);
  }

  @Test
  void anEndorsementPremiumUpdateThatMissesTheRowFailsBeforeTheAudit() {
    lockable();
    when(endorsements.nextSequence(NUMBER)).thenReturn(1);
    when(policies.updateTotalPremium(eq(NUMBER), any(), any(), anyString())).thenReturn(0);

    assertThatThrownBy(
            () ->
                endorsementService(false)
                    .endorse(NUMBER, new EndorsementCommand("CAD", null, null), WHOLE_BOOK, ACTOR))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected 1");
    verify(audit, never()).insert(any());
    verify(outbox, never()).append(any());
  }

  @Test
  void aRenewalUpdateThatMissesTheRowFailsBeforeTheCoveragesAndAudit() {
    lockable();
    when(policies.updateRenewal(any())).thenReturn(0);
    RenewalService service =
        new RenewalService(
            policies,
            audit,
            new RenewalCalculator(properties(false).renewalRules()),
            OpenClaimsRule.NOT_ENFORCED,
            CLOCK,
            outbox);

    assertThatThrownBy(() -> service.renew(NUMBER, null, WHOLE_BOOK, ACTOR))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected 1");
    verify(policies, never()).redateActiveCoverages(any(), any(), any());
    verify(audit, never()).insert(any());
    verify(outbox, never()).append(any());
  }

  @Test
  void principalsThatFitAreKeptAndLongerOnesAreCutWithTheirDigest() {
    assertThat(new Actor("u".repeat(255), null, null).principal()).isEqualTo("u".repeat(255));

    String longAscii = new Actor("a".repeat(256), null, null).principal();
    String otherLongAscii = new Actor("a".repeat(257), null, null).principal();
    assertThat(longAscii).hasSize(255).startsWith("aaaa").contains("#sha256:");
    assertThat(longAscii).isNotEqualTo(otherLongAscii);

    String multibyte = new Actor("é".repeat(200), null, null).principal();
    assertThat(multibyte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(Actor.PRINCIPAL_BYTES);
    assertThat(multibyte).contains("#sha256:");
  }
}
