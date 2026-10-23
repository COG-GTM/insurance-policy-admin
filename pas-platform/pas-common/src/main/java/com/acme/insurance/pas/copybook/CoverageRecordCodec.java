package com.acme.insurance.pas.copybook;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.Coverage;
import com.acme.insurance.pas.domain.CoverageStatus;
import com.acme.insurance.pas.domain.CoverageType;
import java.math.BigDecimal;

/** Maps fixed-width coverage records; nullable numeric values become zero in output (Q-26). */
public final class CoverageRecordCodec {
  private final CopybookCodec codec;

  public CoverageRecordCodec(CopybookCharset charset) {
    codec = new CopybookCodec(PasCopybooks.COVERAGE_RECORD, charset);
  }

  public Coverage decode(byte[] bytes) {
    CopybookCodec.RecordReader reader = codec.reader(bytes);
    return new Coverage(
        reader.string("COV-POLICY-NUMBER"),
        reader.integer("COV-SEQUENCE-NUM"),
        Coded.of(CoverageType.class, reader.string("COV-TYPE-CODE")),
        reader.string("COV-DESCRIPTION"),
        reader.decimal("COV-LIMIT"),
        reader.decimal("COV-DEDUCTIBLE"),
        reader.decimal("COV-PREMIUM"),
        reader.date("COV-EFFECTIVE-DATE"),
        reader.date("COV-EXPIRY-DATE"),
        Coded.of(CoverageStatus.class, reader.string("COV-STATUS")),
        reader.integer("COV-COINSURANCE-PCT"),
        reader.string("COV-RATING-TERRITORY"),
        reader.string("COV-CLASS-CODE"));
  }

  public byte[] encode(Coverage value) {
    return codec
        .writer()
        .putString("COV-POLICY-NUMBER", value.policyNumber())
        .putInteger("COV-SEQUENCE-NUM", value.sequenceNum())
        .putString("COV-TYPE-CODE", raw(value.coverageType()))
        .putString("COV-DESCRIPTION", value.description())
        .putDecimal("COV-LIMIT", zero(value.coverageLimit()))
        .putDecimal("COV-DEDUCTIBLE", zero(value.deductible()))
        .putDecimal("COV-PREMIUM", zero(value.premium()))
        .putDate("COV-EFFECTIVE-DATE", value.effectiveDate())
        .putDate("COV-EXPIRY-DATE", value.expiryDate())
        .putString("COV-STATUS", raw(value.status()))
        .putInteger("COV-COINSURANCE-PCT", value.coinsurancePct())
        .putString("COV-RATING-TERRITORY", value.ratingTerritory())
        .putString("COV-CLASS-CODE", value.classCode())
        .toByteArray();
  }

  private static String raw(Coded<?> coded) {
    return coded == null ? null : coded.raw();
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
