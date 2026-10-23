package com.acme.insurance.pas.copybook;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.InstallmentCode;
import com.acme.insurance.pas.domain.Premium;
import java.math.BigDecimal;

/**
 * Maps the premium copybook to the target model; nullable numerics become zero in output (Q-26).
 */
public final class PremiumRecordCodec {
  private final CopybookCodec codec;

  public PremiumRecordCodec(CopybookCharset charset) {
    codec = new CopybookCodec(PasCopybooks.PREMIUM_RECORD, charset);
  }

  public Premium decode(byte[] bytes) {
    CopybookCodec.RecordReader reader = codec.reader(bytes);
    return new Premium(
        reader.string("PREM-POLICY-NUMBER"),
        reader.integer("PREM-COVERAGE-SEQ"),
        reader.date("PREM-TERM-EFF-DATE"),
        reader.date("PREM-TERM-EXP-DATE"),
        reader.decimal("PREM-BASE-RATE"),
        reader.decimal("PREM-TERRITORY-FACTOR"),
        reader.decimal("PREM-CLASS-FACTOR"),
        reader.decimal("PREM-EXPERIENCE-MOD"),
        reader.decimal("PREM-SCHEDULE-MOD"),
        reader.decimal("PREM-DISCOUNT-PCT"),
        reader.decimal("PREM-SURCHARGE-AMT"),
        reader.decimal("PREM-TAX-AMT"),
        reader.decimal("PREM-TOTAL-PREMIUM"),
        Coded.of(InstallmentCode.class, reader.string("PREM-INSTALLMENT-CODE")),
        reader.decimal("PREM-INSTALLMENT-AMT"),
        reader.date("PREM-CALC-DATE"),
        reader.string("PREM-CALC-BY"));
  }

  public byte[] encode(Premium value) {
    return codec
        .writer()
        .putString("PREM-POLICY-NUMBER", value.policyNumber())
        .putInteger("PREM-COVERAGE-SEQ", value.coverageSeq())
        .putDate("PREM-TERM-EFF-DATE", value.termEffectiveDate())
        .putDate("PREM-TERM-EXP-DATE", value.termExpiryDate())
        .putDecimal("PREM-BASE-RATE", zero(value.baseRate()))
        .putDecimal("PREM-TERRITORY-FACTOR", zero(value.territoryFactor()))
        .putDecimal("PREM-CLASS-FACTOR", zero(value.classFactor()))
        .putDecimal("PREM-EXPERIENCE-MOD", zero(value.experienceMod()))
        .putDecimal("PREM-SCHEDULE-MOD", zero(value.scheduleMod()))
        .putDecimal("PREM-DISCOUNT-PCT", zero(value.discountPct()))
        .putDecimal("PREM-SURCHARGE-AMT", zero(value.surchargeAmt()))
        .putDecimal("PREM-TAX-AMT", zero(value.taxAmt()))
        .putDecimal("PREM-TOTAL-PREMIUM", zero(value.totalPremium()))
        .putString("PREM-INSTALLMENT-CODE", raw(value.installmentCode()))
        .putDecimal("PREM-INSTALLMENT-AMT", zero(value.installmentAmt()))
        .putDate("PREM-CALC-DATE", value.calcDate())
        .putString("PREM-CALC-BY", value.calcBy())
        .toByteArray();
  }

  private static String raw(Coded<?> coded) {
    return coded == null ? null : coded.raw();
  }

  private static BigDecimal zero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
