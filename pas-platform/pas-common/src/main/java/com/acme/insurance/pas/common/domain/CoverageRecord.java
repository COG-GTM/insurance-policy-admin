package com.acme.insurance.pas.common.domain;

import static com.acme.insurance.pas.common.domain.PolicyRecord.whole;

import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.copybook.RecordValues;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Optional;

/** COVERAGE-RECORD.cpy as a value type. */
public record CoverageRecord(
    String policyNumber,
    int sequenceNum,
    String coverageTypeCode,
    String description,
    BigDecimal coverageLimit,
    BigDecimal deductible,
    BigDecimal premium,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String statusCode,
    int coinsurancePct,
    String ratingTerritory,
    String classCode) {

  public Optional<CoverageType> coverageType() {
    return CopybookCode.lookup(CoverageType.class, coverageTypeCode);
  }

  public Optional<CoverageStatus> status() {
    return CopybookCode.lookup(CoverageStatus.class, statusCode);
  }

  public static CoverageRecord fromBytes(byte[] record, Charset charset) {
    RecordValues v = PasCopybooks.COVERAGE_RECORD.decode(record, charset);
    return new CoverageRecord(
        v.string("COV-POLICY-NUMBER"),
        v.integer("COV-SEQUENCE-NUM"),
        v.string("COV-TYPE-CODE"),
        v.string("COV-DESCRIPTION"),
        v.decimal("COV-LIMIT"),
        v.decimal("COV-DEDUCTIBLE"),
        v.decimal("COV-PREMIUM"),
        v.date("COV-EFFECTIVE-DATE"),
        v.date("COV-EXPIRY-DATE"),
        v.string("COV-STATUS"),
        v.integer("COV-COINSURANCE-PCT"),
        v.string("COV-RATING-TERRITORY"),
        v.string("COV-CLASS-CODE"));
  }

  public byte[] toBytes(Charset charset) {
    RecordValues v =
        new RecordValues()
            .put("COV-POLICY-NUMBER", policyNumber)
            .put("COV-SEQUENCE-NUM", whole(sequenceNum))
            .put("COV-TYPE-CODE", coverageTypeCode)
            .put("COV-DESCRIPTION", description)
            .put("COV-LIMIT", coverageLimit)
            .put("COV-DEDUCTIBLE", deductible)
            .put("COV-PREMIUM", premium)
            .put("COV-EFFECTIVE-DATE", effectiveDate)
            .put("COV-EXPIRY-DATE", expiryDate)
            .put("COV-STATUS", statusCode)
            .put("COV-COINSURANCE-PCT", whole(coinsurancePct))
            .put("COV-RATING-TERRITORY", ratingTerritory)
            .put("COV-CLASS-CODE", classCode);
    return PasCopybooks.COVERAGE_RECORD.encode(v, charset);
  }
}
