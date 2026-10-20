package com.acme.insurance.pas.policy.lifecycle;

import com.acme.insurance.pas.policy.persistence.CoverageEntity;
import com.acme.insurance.pas.policy.persistence.CoverageId;
import com.acme.insurance.pas.policy.persistence.PolicyEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * POLNEW business rules, ported from {@code 3000-VALIDATE-INPUT}, {@code 5000-INSERT-POLICY} and
 * {@code 6000-INSERT-COVERAGES}. Pinned by the {@code polnew} parity goldens; intentional changes
 * are the quirk register Q-27 (policy numbers, see {@code PolicyNumberGenerator}) and Q-28 (dates
 * and coverage amounts) entries in {@code parity/known-differences.json}.
 */
public final class NewBusinessRules {

  public static final String HOLDER_REQUIRED = "POLICYHOLDER ID IS REQUIRED";
  public static final String TYPE_REQUIRED = "POLICY TYPE IS REQUIRED";
  public static final String EFFECTIVE_IN_PAST = "EFFECTIVE DATE CANNOT BE IN PAST";
  public static final String LIMIT_ZERO = "POLICY LIMIT MUST BE GREATER THAN ZERO";
  public static final String HOLDER_NOT_FOUND = "POLICYHOLDER NOT FOUND IN SYSTEM";

  /** {@code POL-UPD-BY} written by POLNEW. */
  public static final String UPDATED_BY = "POLNEW";

  static final String STATUS_PENDING = "PN";
  static final String UW_PENDING = "PN";
  static final String FLAG_NO = "N";
  static final String COVERAGE_ACTIVE = "AC";

  /** COVERAGES.COINSURANCE_PCT DB default; POLNEW's INSERT omits the column. */
  static final short COINSURANCE_DEFAULT = 100;

  /** One default coverage written by {@code 6000-INSERT-COVERAGES}. */
  public record DefaultCoverage(String coverageType, String description) {}

  /** Hardcoded in POLNEW ("TODO: This should be table-driven"); LIF/HLT and unknown get none. */
  private static final Map<String, List<DefaultCoverage>> DEFAULT_COVERAGES =
      Map.of(
          "AUT",
          List.of(
              new DefaultCoverage("AUTL", "Auto Liability"),
              new DefaultCoverage("AUTP", "Auto Physical Damage")),
          "HOM",
          List.of(
              new DefaultCoverage("PROP", "Dwelling Coverage"),
              new DefaultCoverage("LIAB", "Personal Liability")),
          "COM",
          List.of(
              new DefaultCoverage("PROP", "Commercial Property"),
              new DefaultCoverage("LIAB", "General Liability"),
              new DefaultCoverage("WKCP", "Workers Compensation")));

  private NewBusinessRules() {}

  /**
   * POLNEW validation. Every check runs and each failure overwrites the message, so the
   * <em>last</em> failing check is reported (quirk register Q-29, preserved): holder-not-found &gt;
   * zero limit &gt; past effective date &gt; blank type &gt; blank holder id. Only a zero limit
   * fails; a negative limit passes, as in COBOL ({@code POLICY-LIMIT = ZEROS}).
   *
   * @param holderFound whether the POLICY_HOLDERS lookup for the given id found a row
   */
  public static Optional<String> validate(
      CreatePolicyRequest request, LocalDate today, boolean holderFound) {
    String message = null;
    if (isBlank(request.policyholderId())) {
      message = HOLDER_REQUIRED;
    }
    if (isBlank(request.policyType())) {
      message = TYPE_REQUIRED;
    }
    if (request.effectiveDate() == null || request.effectiveDate().isBefore(today)) {
      message = EFFECTIVE_IN_PAST;
    }
    if (request.coverageLimit() == null || request.coverageLimit().signum() == 0) {
      message = LIMIT_ZERO;
    }
    if (!holderFound) {
      message = HOLDER_NOT_FOUND;
    }
    return Optional.ofNullable(message);
  }

  public static List<DefaultCoverage> defaultCoveragesFor(String policyType) {
    return policyType == null ? List.of() : DEFAULT_COVERAGES.getOrDefault(policyType, List.of());
  }

  /**
   * The POLICIES row POLNEW inserts. Q-28 fix: effective/expiry dates come from the request and
   * inception = effective (POLNEW never moved them to the host variables).
   */
  public static PolicyEntity newPolicy(
      CreatePolicyRequest request, String policyNumber, LocalDateTime now) {
    PolicyEntity p = new PolicyEntity();
    p.setPolicyNumber(policyNumber);
    p.setPolicyType(request.policyType());
    p.setPolicyStatus(STATUS_PENDING);
    p.setEffectiveDate(request.effectiveDate());
    p.setExpiryDate(request.expiryDate());
    p.setPolicyholderId(request.policyholderId());
    p.setAgentCode(request.agentCode());
    p.setBranchCode(request.branchCode());
    p.setTotalPremium(amount(request.totalPremium()));
    p.setDeductible(amount(request.deductible()));
    p.setCoverageLimit(amount(request.coverageLimit()));
    p.setInceptionDate(request.effectiveDate());
    p.setRenewalCount((short) 0);
    p.setUwStatus(UW_PENDING);
    p.setRiskScore((short) 0);
    p.setWebIndicator(FLAG_NO);
    p.setApiFlag(FLAG_NO);
    p.setLastUpdated(now);
    p.setUpdatedBy(UPDATED_BY);
    return p;
  }

  /**
   * COVERAGES rows for the policy type, numbered from 1. Q-28 fix (proposed rule, Pending: PO):
   * each coverage takes the policy's dates, limit and deductible; premium stays 0.00 until the
   * premium batch rates the policy.
   */
  public static List<CoverageEntity> defaultCoverages(PolicyEntity policy) {
    List<CoverageEntity> out = new ArrayList<>();
    short seq = 0;
    for (DefaultCoverage d : defaultCoveragesFor(policy.getPolicyType())) {
      seq++;
      CoverageEntity c = new CoverageEntity();
      c.setId(new CoverageId(policy.getPolicyNumber(), seq));
      c.setCoverageType(d.coverageType());
      c.setDescription(d.description());
      c.setCoverageLimit(policy.getCoverageLimit());
      c.setDeductible(policy.getDeductible());
      c.setPremium(amount(null));
      c.setEffectiveDate(policy.getEffectiveDate());
      c.setExpiryDate(policy.getExpiryDate());
      c.setStatus(COVERAGE_ACTIVE);
      c.setCoinsurancePct(COINSURANCE_DEFAULT);
      out.add(c);
    }
    return out;
  }

  static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static BigDecimal amount(BigDecimal value) {
    return CreatePolicyRequest.cents(value == null ? BigDecimal.ZERO : value);
  }
}
