package com.acme.insurance.pas.domain.copybook;

import com.acme.insurance.pas.domain.Premium;
import java.nio.charset.Charset;

/** PREMIUM-RECORD (105 bytes). */
public final class PremiumRecordCodec extends CopybookCodec<Premium> {

  public PremiumRecordCodec() {
    this(EBCDIC);
  }

  public PremiumRecordCodec(Charset charset) {
    super(CopybookLayouts.PREMIUM_RECORD, charset);
  }

  @Override
  protected Premium read(RecordReader in) {
    return new Premium(
        in.text("PREM-POLICY-NUMBER"),
        in.number("PREM-COVERAGE-SEQ"),
        in.date("PREM-TERM-EFF-DATE"),
        in.date("PREM-TERM-EXP-DATE"),
        in.decimal("PREM-BASE-RATE"),
        in.decimal("PREM-TERRITORY-FACTOR"),
        in.decimal("PREM-CLASS-FACTOR"),
        in.decimal("PREM-EXPERIENCE-MOD"),
        in.decimal("PREM-SCHEDULE-MOD"),
        in.decimal("PREM-DISCOUNT-PCT"),
        in.decimal("PREM-SURCHARGE-AMT"),
        in.decimal("PREM-TAX-AMT"),
        in.decimal("PREM-TOTAL-PREMIUM"),
        in.text("PREM-INSTALLMENT-CODE"),
        in.decimal("PREM-INSTALLMENT-AMT"),
        in.date("PREM-CALC-DATE"),
        in.text("PREM-CALC-BY"));
  }

  @Override
  protected void write(Premium p, RecordWriter out) {
    out.text("PREM-POLICY-NUMBER", p.policyNumber())
        .number("PREM-COVERAGE-SEQ", p.coverageSeq())
        .date("PREM-TERM-EFF-DATE", p.termEffectiveDate())
        .date("PREM-TERM-EXP-DATE", p.termExpiryDate())
        .decimal("PREM-BASE-RATE", p.baseRate())
        .decimal("PREM-TERRITORY-FACTOR", p.territoryFactor())
        .decimal("PREM-CLASS-FACTOR", p.classFactor())
        .decimal("PREM-EXPERIENCE-MOD", p.experienceMod())
        .decimal("PREM-SCHEDULE-MOD", p.scheduleMod())
        .decimal("PREM-DISCOUNT-PCT", p.discountPct())
        .decimal("PREM-SURCHARGE-AMT", p.surchargeAmt())
        .decimal("PREM-TAX-AMT", p.taxAmt())
        .decimal("PREM-TOTAL-PREMIUM", p.totalPremium())
        .text("PREM-INSTALLMENT-CODE", p.installmentCode())
        .decimal("PREM-INSTALLMENT-AMT", p.installmentAmt())
        .date("PREM-CALC-DATE", p.calcDate())
        .text("PREM-CALC-BY", p.calcBy());
  }
}
