package com.acme.insurance.pas.policyservice.change;

import com.acme.insurance.pas.domain.Endorsement;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.events.PasEvents;
import com.acme.insurance.pas.events.PolicyEndorsed;
import com.acme.insurance.pas.persistence.EndorsementJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyChangeAudit;
import com.acme.insurance.pas.persistence.PolicyChangeAuditJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.policyservice.change.PolicyChangeConfiguration.PolicyChangeClock;
import com.acme.insurance.pas.policyservice.events.OutboxWriter;
import com.acme.insurance.pas.policyservice.security.Entitlement;
import com.acme.insurance.pas.rating.PolicyChangeError;
import com.acme.insurance.pas.rating.ProRata;
import com.acme.insurance.pas.rating.RatingOverflowException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mid-term endorsement (replaces CICS PEND / POLEND). One transaction under the policy row lock
 * (POLEND's {@code KEEP UPDATE LOCKS}): the ENDORSEMENTS row, the premium adjustment on POLICIES
 * and the durable audit row (Q-23) are written together or not at all (fixes Q-22). Type-specific
 * processing, including status changes for CAN, is not in POLEND either (Q-21, FU-08).
 */
@Service
public class EndorsementService {

  /** PROCESSED_BY / UPDATED_BY keep the legacy program id until Q-518-1 is signed off. */
  static final String PROCESSED_BY = "POLEND";

  private final PolicyJdbcRepository policies;
  private final EndorsementJdbcRepository endorsements;
  private final PolicyChangeAuditJdbcRepository audit;
  private final PolicyChangeClock clock;
  private final boolean premiumChangeEnabled;
  private final OutboxWriter outbox;
  private final PasEvents events;

  public EndorsementService(
      PolicyJdbcRepository policies,
      EndorsementJdbcRepository endorsements,
      PolicyChangeAuditJdbcRepository audit,
      PolicyChangeClock clock,
      PolicyChangeProperties properties,
      OutboxWriter outbox) {
    this.outbox = outbox;
    this.events = new PasEvents(clock.clock());
    this.policies = policies;
    this.endorsements = endorsements;
    this.audit = audit;
    this.clock = clock;
    this.premiumChangeEnabled = properties.endorsementPremiumChangeEnabled();
  }

  @Transactional
  public ChangeResult<EndorsementOutcome> endorse(
      String policyNumber, EndorsementCommand command, Entitlement entitlement, Actor actor) {
    var invalidType = PolicyChangeError.checkEndorsementType(command.endorsementType());
    if (invalidType.isPresent()) {
      return ChangeResult.rejected(invalidType.get());
    }
    BigDecimal change = command.annualPremiumChange();
    if (!premiumChangeEnabled && change != null && change.signum() != 0) {
      return ChangeResult.rejected(PolicyChangeError.ENDORSEMENT_PREMIUM_CHANGE_DISABLED);
    }
    ChangeResult<Policy> lookup = PolicyLookup.lockEntitled(policies, policyNumber, entitlement);
    if (lookup.outcome() != ChangeResult.Outcome.DONE) {
      return PolicyLookup.propagate(lookup);
    }
    Policy current = lookup.value();
    if (!current.policyStatus().map(PolicyStatus::endorsable).orElse(false)) {
      return ChangeResult.rejected(PolicyChangeError.NOT_ACTIVE_FOR_ENDORSEMENT);
    }
    LocalDateTime now = LocalDateTime.now(clock.clock());
    LocalDate businessDate = now.toLocalDate();
    ProRata proRata;
    BigDecimal newPremium;
    try {
      proRata = ProRata.of(current.effectiveDate(), current.expiryDate(), businessDate, change);
      newPremium = proRata.applyTo(current.totalPremium());
    } catch (RatingOverflowException e) {
      return ChangeResult.rejected(PolicyChangeError.PREMIUM_OUT_OF_RANGE);
    } catch (IllegalArgumentException e) {
      return ChangeResult.rejected(PolicyChangeError.POLICY_TERM_INVALID);
    }
    int seq = endorsements.nextSequence(current.policyNumber());
    Endorsement endorsement =
        new Endorsement(
            current.policyNumber(),
            seq,
            command.endorsementType(),
            businessDate,
            command.description(),
            proRata.adjustment(),
            now,
            PROCESSED_BY);
    endorsements.insert(endorsement);
    PolicyLookup.requireOneRow(
        policies.updateTotalPremium(current.policyNumber(), newPremium, now, PROCESSED_BY),
        "POLICIES premium update",
        current.policyNumber());
    audit.insert(
        new PolicyChangeAudit(
            current.policyNumber(),
            PolicyChangeAudit.Operation.ENDORSEMENT,
            PROCESSED_BY,
            seq,
            command.endorsementType(),
            actor.principal(),
            actor.clientId(),
            actor.correlationId(),
            current.policyStatusCode(),
            current.policyStatusCode(),
            current.effectiveDate(),
            current.expiryDate(),
            current.effectiveDate(),
            current.expiryDate(),
            current.totalPremium(),
            proRata.adjustment(),
            newPremium,
            current.renewalCount(),
            now));
    outbox.append(
        events.policyEndorsed(
            PolicyEndorsed.of(
                current,
                seq,
                command.endorsementType(),
                businessDate,
                proRata.adjustment(),
                newPremium),
            actor.correlationId()));
    return ChangeResult.done(
        new EndorsementOutcome(endorsement, proRata, current.policyStatusCode(), newPremium));
  }

  /**
   * @param endorsementType CAD, CRM, LCH, ACH or CAN (required, Q-19)
   * @param description optional free text, at most 100 characters
   * @param annualPremiumChange full-term premium change the pro-rata factor applies to; absent is
   *     0.00, POLEND's adjustment (Q-20). Non-zero only with {@code
   *     pas.policy-change.endorsement-premium-change-enabled} (FU-526-1)
   */
  public record EndorsementCommand(
      String endorsementType, String description, BigDecimal annualPremiumChange) {}

  /** The written endorsement, its pro-rata calculation and the policy afterwards. */
  public record EndorsementOutcome(
      Endorsement endorsement, ProRata proRata, String policyStatus, BigDecimal totalPremium) {}
}
