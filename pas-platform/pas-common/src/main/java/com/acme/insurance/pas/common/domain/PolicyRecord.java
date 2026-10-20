package com.acme.insurance.pas.common.domain;

import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.copybook.RecordValues;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Optional;

/**
 * POLICY-RECORD.cpy as a value type. Code fields hold the raw copybook value; the typed accessors
 * resolve them to 88-level enums. Zero dates decode to {@code null}.
 */
public record PolicyRecord(
    String policyNumber,
    String policyTypeCode,
    String policyStatusCode,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String holderId,
    String agentCode,
    String branchCode,
    BigDecimal totalPremium,
    BigDecimal deductible,
    BigDecimal coverageLimit,
    LocalDate inceptionDate,
    int renewalCount,
    String uwStatusCode,
    int riskScore,
    String webIndicator,
    String apiFlag,
    LocalDate lastUpdated,
    String updatedBy) {

  public Optional<PolicyType> policyType() {
    return CopybookCode.lookup(PolicyType.class, policyTypeCode);
  }

  public Optional<PolicyStatus> policyStatus() {
    return CopybookCode.lookup(PolicyStatus.class, policyStatusCode);
  }

  public Optional<UnderwritingStatus> uwStatus() {
    return CopybookCode.lookup(UnderwritingStatus.class, uwStatusCode);
  }

  public static PolicyRecord fromBytes(byte[] record, Charset charset) {
    RecordValues v = PasCopybooks.POLICY_RECORD.decode(record, charset);
    return new PolicyRecord(
        v.string("POLICY-NUMBER"),
        v.string("POLICY-TYPE"),
        v.string("POLICY-STATUS"),
        v.date("POLICY-EFFECTIVE-DATE"),
        v.date("POLICY-EXPIRY-DATE"),
        v.string("POLICY-HOLDER-ID"),
        v.string("POLICY-AGENT-CODE"),
        v.string("POLICY-BRANCH-CODE"),
        v.decimal("POLICY-TOTAL-PREMIUM"),
        v.decimal("POLICY-DEDUCTIBLE"),
        v.decimal("POLICY-LIMIT"),
        v.date("POLICY-INCEPTION-DATE"),
        v.integer("POLICY-RENEWAL-COUNT"),
        v.string("POLICY-UW-STATUS"),
        v.integer("POLICY-RISK-SCORE"),
        v.string("POLICY-WEB-IND"),
        v.string("POLICY-API-FLAG"),
        v.date("POLICY-LAST-UPDATED"),
        v.string("POLICY-UPDATED-BY"));
  }

  public byte[] toBytes(Charset charset) {
    RecordValues v =
        new RecordValues()
            .put("POLICY-NUMBER", policyNumber)
            .put("POLICY-TYPE", policyTypeCode)
            .put("POLICY-STATUS", policyStatusCode)
            .put("POLICY-EFFECTIVE-DATE", effectiveDate)
            .put("POLICY-EXPIRY-DATE", expiryDate)
            .put("POLICY-HOLDER-ID", holderId)
            .put("POLICY-AGENT-CODE", agentCode)
            .put("POLICY-BRANCH-CODE", branchCode)
            .put("POLICY-TOTAL-PREMIUM", totalPremium)
            .put("POLICY-DEDUCTIBLE", deductible)
            .put("POLICY-LIMIT", coverageLimit)
            .put("POLICY-INCEPTION-DATE", inceptionDate)
            .put("POLICY-RENEWAL-COUNT", whole(renewalCount))
            .put("POLICY-UW-STATUS", uwStatusCode)
            .put("POLICY-RISK-SCORE", whole(riskScore))
            .put("POLICY-WEB-IND", webIndicator)
            .put("POLICY-API-FLAG", apiFlag)
            .put("POLICY-LAST-UPDATED", lastUpdated)
            .put("POLICY-UPDATED-BY", updatedBy);
    return PasCopybooks.POLICY_RECORD.encode(v, charset);
  }

  static BigDecimal whole(int value) {
    return new BigDecimal(BigInteger.valueOf(value));
  }
}
