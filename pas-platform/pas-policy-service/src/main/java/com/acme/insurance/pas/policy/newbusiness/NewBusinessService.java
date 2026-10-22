package com.acme.insurance.pas.policy.newbusiness;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import com.acme.insurance.pas.domain.Policy;
import com.acme.insurance.pas.domain.PolicyStatus;
import com.acme.insurance.pas.domain.PolicyType;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.policy.events.OutboxWriter;
import com.acme.insurance.pas.policy.inquiry.CallerEntitlements;
import com.acme.insurance.pas.policy.inquiry.PolicyResponse;
import com.acme.insurance.pas.policy.inquiry.PolicyResponseMapper;
import com.acme.insurance.pas.policy.platform.ApiProblem;
import com.acme.insurance.pas.policy.repository.CoverageRepository;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.acme.insurance.pas.policy.repository.PolicyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewBusinessService {
  private final PolicyRepository policies;
  private final CoverageRepository coverages;
  private final PolicyHolderRepository holders;
  private final PolicyNumberGenerator policyNumbers;
  private final IdempotencyRepository idempotency;
  private final ShadowNewBusinessRepository shadowResults;
  private final DefaultCoverageProperties defaultCoverageProperties;
  private final OutboxWriter outboxWriter;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Dependencies are Spring-managed services and immutable configuration.")
  public NewBusinessService(
      PolicyRepository policies,
      CoverageRepository coverages,
      PolicyHolderRepository holders,
      PolicyNumberGenerator policyNumbers,
      IdempotencyRepository idempotency,
      ShadowNewBusinessRepository shadowResults,
      DefaultCoverageProperties defaultCoverageProperties,
      OutboxWriter outboxWriter,
      ObjectMapper objectMapper,
      Clock clock) {
    this.policies = policies;
    this.coverages = coverages;
    this.holders = holders;
    this.policyNumbers = policyNumbers;
    this.idempotency = idempotency;
    this.shadowResults = shadowResults;
    this.defaultCoverageProperties = defaultCoverageProperties;
    this.outboxWriter = outboxWriter;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public CreatePolicyResult create(
      NewBusinessRequest request,
      String idempotencyKey,
      String subject,
      CallerEntitlements entitlements,
      boolean shadow,
      String correlationId) {
    if (idempotencyKey != null && idempotencyKey.length() > 255) {
      return problem(HttpStatus.BAD_REQUEST, "Idempotency-Key must not exceed 255 characters");
    }

    String requestHash = requestHash(request);
    String shadowRequestHash = shadowRequestHash(request);
    if (idempotencyKey != null && !shadow) {
      Optional<IdempotencyRepository.StoredResponse> stored =
          idempotency.find(subject, idempotencyKey);
      if (stored.isPresent()) {
        return replayOrConflict(requestHash, stored.get());
      }
    }

    List<NewBusinessError> errors = validate(request);
    if (!errors.isEmpty()) {
      if (shadow) {
        if (!entitledToWrite(entitlements, request)) {
          return problem(
              HttpStatus.FORBIDDEN, "Caller is not entitled to write this agent or branch");
        }
        shadowResults.insert(
            shadowRequestHash,
            validatedPolicyType(request.policyType(), errors),
            defaultCoverageTypes(request.policyType()),
            errors.stream().map(NewBusinessError::field).distinct().toList());
        return new CreatePolicyResult(
            HttpStatus.ACCEPTED.value(), null, new ShadowResponse(true, "INVALID", errors), false);
      }
      return new CreatePolicyResult(
          HttpStatus.UNPROCESSABLE_ENTITY.value(),
          null,
          new ApiProblem(
              "about:blank",
              "Unprocessable Entity",
              422,
              "Policy request validation failed",
              errors),
          true);
    }

    if (!entitledToWrite(entitlements, request)) {
      return problem(HttpStatus.FORBIDDEN, "Caller is not entitled to write this agent or branch");
    }

    if (!holders.existsByCustId(request.policyholderId().trim())) {
      NewBusinessError error = new NewBusinessError("policyholderId", "POLICYHOLDER NOT FOUND");
      if (shadow) {
        shadowResults.insert(
            shadowRequestHash,
            request.policyType(),
            defaultCoverageTypes(request.policyType()),
            List.of(error.field()));
        return new CreatePolicyResult(
            HttpStatus.ACCEPTED.value(),
            null,
            new ShadowResponse(true, "INVALID", List.of(error)),
            false);
      }
      return new CreatePolicyResult(
          HttpStatus.UNPROCESSABLE_ENTITY.value(),
          null,
          new ApiProblem(
              "about:blank",
              "Unprocessable Entity",
              422,
              "Policy request validation failed",
              List.of(error)),
          true);
    }

    if (shadow) {
      shadowResults.insert(
          shadowRequestHash,
          request.policyType(),
          defaultCoverageTypes(request.policyType()),
          List.of());
      return new CreatePolicyResult(
          HttpStatus.ACCEPTED.value(), null, new ShadowResponse(true, "VALID", List.of()), false);
    }

    if (idempotencyKey != null) {
      if (!idempotency.insertIfAbsent(subject, idempotencyKey, requestHash)) {
        Optional<IdempotencyRepository.StoredResponse> stored =
            idempotency.find(subject, idempotencyKey);
        if (stored.isEmpty()) {
          throw new IllegalStateException("Idempotency row disappeared after conflict");
        }
        return replayOrConflict(requestHash, stored.get());
      }
    }

    Policy policy = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      String policyNumber = policyNumbers.next();
      Policy candidate = toPolicy(policyNumber, request, subject);
      if (policies.insertIfAbsent(candidate)) {
        policy = candidate;
        break;
      }
    }
    if (policy == null) {
      throw new PolicyNumberCollisionException();
    }

    insertDefaultCoverages(policy, request);
    List<Coverage> createdCoverages = coverages.findByPolicyNumber(policy.policyNumber());
    outboxWriter.append(
        OutboxWriter.POLICY_TOPIC,
        "PolicyCreated",
        policy.policyNumber(),
        1,
        correlationId,
        policyCreatedData(policy, createdCoverages));
    Map<String, Object> referral = new LinkedHashMap<>();
    referral.put("trigger", "NEW_BUSINESS");
    referral.put("decisionId", null);
    referral.put("decisionCode", null);
    referral.put("riskScore", null);
    referral.put("reasons", null);
    referral.put("lossRatio", null);
    referral.put("override", null);
    referral.put("overridesDecisionId", null);
    outboxWriter.append(
        OutboxWriter.UNDERWRITING_TOPIC,
        "UnderwritingReferralRequested",
        policy.policyNumber(),
        1,
        correlationId,
        referral);
    PolicyResponse response = PolicyResponseMapper.map(policy);
    String location = "/api/v1/policies/" + policy.policyNumber().trim();
    if (idempotencyKey != null) {
      idempotency.saveResponse(
          subject, idempotencyKey, HttpStatus.CREATED.value(), location, toJson(response));
    }
    return new CreatePolicyResult(HttpStatus.CREATED.value(), location, response, false);
  }

  List<NewBusinessError> validate(NewBusinessRequest request) {
    List<NewBusinessError> errors = new ArrayList<>();
    if (isBlank(request.policyholderId())) {
      errors.add(new NewBusinessError("policyholderId", "POLICYHOLDER ID IS REQUIRED"));
    } else if (request.policyholderId().trim().length() > 10) {
      errors.add(
          new NewBusinessError("policyholderId", "policyholderId must be at most 10 characters"));
    }
    if (isBlank(request.policyType())) {
      errors.add(new NewBusinessError("policyType", "POLICY TYPE IS REQUIRED"));
    } else if (request.policyType().trim().length() > 3) {
      errors.add(new NewBusinessError("policyType", "policyType must be at most 3 characters"));
    }
    if (request.effectiveDate() == null) {
      errors.add(new NewBusinessError("effectiveDate", "EFFECTIVE DATE IS REQUIRED"));
    } else if (request.effectiveDate().isBefore(LocalDate.now(clock))) {
      errors.add(new NewBusinessError("effectiveDate", "EFFECTIVE DATE CANNOT BE IN PAST"));
    }
    if (request.coverageLimit() == null || request.coverageLimit().signum() <= 0) {
      errors.add(new NewBusinessError("coverageLimit", "POLICY LIMIT MUST BE GREATER THAN ZERO"));
    }
    addNumericError(errors, "coverageLimit", request.coverageLimit(), 13, 2);
    addNumericError(errors, "deductible", request.deductible(), 9, 2);
    addNumericError(errors, "totalPremium", request.totalPremium(), 11, 2);
    addLengthError(errors, "agentCode", request.agentCode(), 6);
    addLengthError(errors, "branchCode", request.branchCode(), 4);
    return List.copyOf(errors);
  }

  private static void addNumericError(
      List<NewBusinessError> errors, String field, BigDecimal value, int precision, int scale) {
    if (value == null) {
      return;
    }
    if (value.scale() > scale || value.precision() - value.scale() > precision - scale) {
      errors.add(
          new NewBusinessError(field, field + " exceeds NUMERIC(" + precision + "," + scale + ")"));
    }
  }

  private static void addLengthError(
      List<NewBusinessError> errors, String field, String value, int maxLength) {
    if (value != null && value.trim().length() > maxLength) {
      errors.add(
          new NewBusinessError(field, field + " must be at most " + maxLength + " characters"));
    }
  }

  private static boolean entitledToWrite(
      CallerEntitlements entitlements, NewBusinessRequest request) {
    if (entitlements.wholeBook()) {
      return true;
    }
    String agent = normalize(request.agentCode());
    String branch = normalize(request.branchCode());
    return (agent != null || branch != null)
        && (agent == null || agent.equals(entitlements.agentId()))
        && (branch == null || entitlements.branchCodes().contains(branch));
  }

  private Policy toPolicy(String policyNumber, NewBusinessRequest request, String subject) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    return new Policy(
        policyNumber,
        Coded.of(PolicyType.class, trimToNull(request.policyType())),
        Coded.of(PolicyStatus.class, "PN"),
        request.effectiveDate(),
        request.effectiveDate().plusYears(1),
        request.policyholderId().trim(),
        trimToNull(request.agentCode()),
        trimToNull(request.branchCode()),
        request.totalPremium(),
        request.deductible(),
        request.coverageLimit(),
        request.effectiveDate(),
        0,
        Coded.of(UnderwritingStatus.class, "PN"),
        0,
        Coded.of(YesNo.class, "N"),
        Coded.of(YesNo.class, "N"),
        now,
        "POLNEW");
  }

  private void insertDefaultCoverages(Policy policy, NewBusinessRequest request) {
    List<DefaultCoverageProperties.DefaultCoverage> defaults =
        defaultCoverageProperties
            .defaultCoverages()
            .getOrDefault(request.policyType().trim(), List.of());
    short sequence = 1;
    for (DefaultCoverageProperties.DefaultCoverage coverage : defaults) {
      coverages.insert(
          new Coverage(
              policy.policyNumber(),
              (int) sequence++,
              Coded.of(CoverageType.class, coverage.type()),
              coverage.description(),
              request.coverageLimit(),
              request.deductible(),
              BigDecimal.ZERO.setScale(2),
              policy.effectiveDate(),
              policy.expiryDate(),
              Coded.of(CoverageStatus.class, "AC"),
              100,
              null,
              null));
    }
  }

  private static Map<String, Object> policyCreatedData(Policy policy, List<Coverage> coverages) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("policyType", policy.policyType().raw());
    data.put("policyStatus", policy.policyStatus().raw());
    data.put("uwStatus", policy.uwStatus().raw());
    data.put("effectiveDate", policy.effectiveDate().toString());
    data.put("expiryDate", policy.expiryDate().toString());
    data.put("agentCode", policy.agentCode());
    data.put("branchCode", policy.branchCode());
    data.put("coverageLimit", policy.coverageLimit());
    data.put("deductible", policy.deductible());
    data.put("totalPremium", policy.totalPremium());
    data.put(
        "coverages",
        coverages.stream()
            .map(
                coverage ->
                    Map.of(
                        "sequenceNum", coverage.sequenceNum(),
                        "coverageType", coverage.coverageType().raw()))
            .toList());
    return data;
  }

  private List<String> defaultCoverageTypes(String policyType) {
    if (policyType == null) {
      return List.of();
    }
    return defaultCoverageProperties
        .defaultCoverages()
        .getOrDefault(policyType.trim(), List.of())
        .stream()
        .map(DefaultCoverageProperties.DefaultCoverage::type)
        .toList();
  }

  String requestHash(NewBusinessRequest request) {
    try {
      byte[] canonical = objectMapper.writeValueAsBytes(request);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to hash policy request", exception);
    }
  }

  private String shadowRequestHash(NewBusinessRequest request) {
    try {
      ObjectNode withoutPolicyholderId = objectMapper.valueToTree(request);
      withoutPolicyholderId.remove("policyholderId");
      byte[] canonical = objectMapper.writeValueAsBytes(withoutPolicyholderId);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to hash shadow policy request", exception);
    }
  }

  private CreatePolicyResult replayOrConflict(
      String requestHash, IdempotencyRepository.StoredResponse stored) {
    if (!requestHash.equals(stored.requestHash())) {
      return problem(HttpStatus.CONFLICT, "Idempotency-Key was already used for another request");
    }
    return replay(stored);
  }

  private static String validatedPolicyType(String policyType, List<NewBusinessError> errors) {
    return errors.stream().anyMatch(error -> "policyType".equals(error.field()))
        ? null
        : policyType;
  }

  private String toJson(Object response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize policy response", exception);
    }
  }

  private CreatePolicyResult replay(IdempotencyRepository.StoredResponse stored) {
    try {
      PolicyResponse response = objectMapper.readValue(stored.body(), PolicyResponse.class);
      return new CreatePolicyResult(stored.status(), stored.location(), response, false);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to deserialize stored policy response", exception);
    }
  }

  private static CreatePolicyResult problem(HttpStatus status, String detail) {
    return new CreatePolicyResult(
        status.value(),
        null,
        ApiProblem.of(status.value(), status.getReasonPhrase(), detail),
        true);
  }

  private static String trimToNull(String value) {
    return value == null ? null : value.trim();
  }

  private static String normalize(String value) {
    return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
