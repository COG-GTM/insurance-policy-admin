package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.events.PasEventProperties;
import com.acme.insurance.pas.policy.events.PasEventTypes;
import com.acme.insurance.pas.policy.inquiry.CoverageView;
import com.acme.insurance.pas.policy.inquiry.PolicyView;
import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.CoverageRepository;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import com.acme.insurance.pas.policy.persistence.PolicyHolderRepository;
import com.acme.insurance.pas.policy.persistence.PolicyNumberGenerator;
import com.acme.insurance.pas.policy.persistence.PolicyRepository;
import com.acme.insurance.pas.policy.security.CallerEntitlements;
import com.acme.insurance.pas.policy.security.NotEntitledException;
import com.acme.insurance.pas.policy.support.BusinessCalendar;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * New business (replaces CICS PNEW / POLNEW): validates, numbers and inserts the policy and its
 * default coverages, and records a {@code PolicyCreated} outbox event in the same transaction.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed singleton collaborators are shared by design")
public class NewBusinessService {

  static final String OPERATION = "create-policy";

  /** Result of a create call; {@code replayed} when an Idempotency-Key matched an earlier call. */
  public record Outcome(CreatedPolicyView view, boolean replayed) {}

  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final PolicyHolderRepository holders;
  private final PolicyNumberGenerator numbers;
  private final IdempotencyStore idempotency;
  private final OutboxWriter outbox;
  private final PasEventProperties topics;
  private final BusinessCalendar calendar;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;

  public NewBusinessService(
      PolicyRepository policies,
      CoverageRepository coverages,
      PolicyHolderRepository holders,
      PolicyNumberGenerator numbers,
      IdempotencyStore idempotency,
      OutboxWriter outbox,
      PasEventProperties topics,
      BusinessCalendar calendar,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.policies = policies;
    this.coverages = coverages;
    this.holders = holders;
    this.numbers = numbers;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.topics = topics;
    this.calendar = calendar;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * Creates a policy. With an Idempotency-Key, a repeat of the same request by the same subject
   * returns the stored first response (not the policy's current state); the same key with a
   * different body is rejected.
   *
   * @throws NotEntitledException the caller may not write business for the agent/branch
   * @throws NewBusinessRejectedException a POLNEW rule or the term check failed
   */
  public Outcome create(
      CreatePolicyRequest request,
      CallerEntitlements caller,
      String subject,
      String idempotencyKey,
      String correlationId) {
    if (!caller.canAccess(request.agentCode(), request.branchCode())) {
      throw new NotEntitledException(
          "caller is not entitled to write business for this agent/branch");
    }
    String hash = idempotencyKey == null ? null : hash(request.normalised());
    try {
      return tx.execute(
          s -> createInTransaction(request, subject, idempotencyKey, hash, correlationId));
    } catch (DuplicateKeyException e) {
      if (idempotencyKey == null) {
        throw e;
      }
      // A concurrent request with the same key committed first: replay its result.
      Optional<Outcome> replayed = tx.execute(s -> replay(subject, idempotencyKey, hash));
      return Objects.requireNonNullElse(replayed, Optional.<Outcome>empty()).orElseThrow(() -> e);
    }
  }

  private Outcome createInTransaction(
      CreatePolicyRequest request, String subject, String key, String hash, String correlationId) {
    if (key != null) {
      Optional<Outcome> earlier = replay(subject, key, hash);
      if (earlier.isPresent()) {
        return earlier.get();
      }
    }
    String holderId = request.policyholderId();
    boolean holderFound = holderId != null && !holderId.isEmpty() && holders.existsById(holderId);
    Optional<String> rejection = NewBusinessRules.validate(request, calendar.today(), holderFound);
    if (rejection.isPresent()) {
      throw new NewBusinessRejectedException(
          NewBusinessRejectedException.POLNEW_VALIDATION, rejection.get());
    }
    if (!request.expiryDate().isAfter(request.effectiveDate())) {
      throw new NewBusinessRejectedException(
          NewBusinessRejectedException.INVALID_TERM, "EXPIRY DATE MUST BE AFTER EFFECTIVE DATE");
    }

    String number = numbers.nextPolicyNumber();
    PolicyEntity policy =
        policies.saveAndFlush(NewBusinessRules.newPolicy(request, number, calendar.nowUtc()));
    List<CoverageEntity> covs =
        coverages.saveAllAndFlush(NewBusinessRules.defaultCoverages(policy));
    outbox.append(
        topics.policyTopic(),
        PasEventTypes.POLICY_CREATED,
        number,
        policy.getVersion(),
        correlationId,
        PolicyCreatedData.of(policy, covs));
    CreatedPolicyView view = view(policy, covs);
    if (key != null) {
      idempotency.insert(subject, OPERATION, key, hash, number, toJson(view));
    }
    return new Outcome(view, false);
  }

  private Optional<Outcome> replay(String subject, String key, String hash) {
    return idempotency
        .find(subject, OPERATION, key)
        .map(
            entry -> {
              if (!entry.requestHash().equals(hash)) {
                throw new NewBusinessRejectedException(
                    NewBusinessRejectedException.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used with a different request");
              }
              return new Outcome(fromJson(entry.responseJson()), true);
            });
  }

  private static CreatedPolicyView view(PolicyEntity policy, List<CoverageEntity> covs) {
    return new CreatedPolicyView(
        PolicyView.from(policy), covs.stream().map(CoverageView::from).toList());
  }

  private String toJson(CreatedPolicyView view) {
    try {
      return mapper.writeValueAsString(view);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise create-policy response", e);
    }
  }

  private CreatedPolicyView fromJson(String json) {
    try {
      return mapper.readValue(json, CreatedPolicyView.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot read stored create-policy response", e);
    }
  }

  private String hash(CreatePolicyRequest request) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request));
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("cannot hash request", e);
    }
  }
}
