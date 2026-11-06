package com.acme.insurance.pas.domain.copybook;

import com.acme.insurance.pas.domain.Coverage;
import java.nio.charset.Charset;

/** COVERAGE-RECORD (124 bytes). */
public final class CoverageRecordCodec extends CopybookCodec<Coverage> {

  public CoverageRecordCodec() {
    this(EBCDIC);
  }

  public CoverageRecordCodec(Charset charset) {
    super(CopybookLayouts.COVERAGE_RECORD, charset);
  }

  @Override
  protected Coverage read(RecordReader in) {
    return new Coverage(
        in.text("COV-POLICY-NUMBER"),
        in.number("COV-SEQUENCE-NUM"),
        in.text("COV-TYPE-CODE"),
        in.text("COV-DESCRIPTION"),
        in.decimal("COV-LIMIT"),
        in.decimal("COV-DEDUCTIBLE"),
        in.decimal("COV-PREMIUM"),
        in.date("COV-EFFECTIVE-DATE"),
        in.date("COV-EXPIRY-DATE"),
        in.text("COV-STATUS"),
        in.number("COV-COINSURANCE-PCT"),
        in.text("COV-RATING-TERRITORY"),
        in.text("COV-CLASS-CODE"));
  }

  @Override
  protected void write(Coverage c, RecordWriter out) {
    out.text("COV-POLICY-NUMBER", c.policyNumber())
        .number("COV-SEQUENCE-NUM", c.sequenceNum())
        .text("COV-TYPE-CODE", c.coverageTypeCode())
        .text("COV-DESCRIPTION", c.description())
        .decimal("COV-LIMIT", c.coverageLimit())
        .decimal("COV-DEDUCTIBLE", c.deductible())
        .decimal("COV-PREMIUM", c.premium())
        .date("COV-EFFECTIVE-DATE", c.effectiveDate())
        .date("COV-EXPIRY-DATE", c.expiryDate())
        .text("COV-STATUS", c.statusCode())
        .number("COV-COINSURANCE-PCT", c.coinsurancePct())
        .text("COV-RATING-TERRITORY", c.ratingTerritory())
        .text("COV-CLASS-CODE", c.classCode());
  }
}
