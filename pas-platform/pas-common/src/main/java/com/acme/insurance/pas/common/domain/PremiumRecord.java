package com.acme.insurance.pas.common.domain;

import static com.acme.insurance.pas.common.domain.PolicyRecord.whole;

import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.copybook.RecordValues;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Optional;

/** PREMIUM-RECORD.cpy as a value type. Rates/factors have scale 4, amounts scale 2. */
public record PremiumRecord(
    String policyNumber,
    int coverageSeq,
    LocalDate termEffectiveDate,
    LocalDate termExpiryDate,
    BigDecimal baseRate,
    BigDecimal territoryFactor,
    BigDecimal classFactor,
    BigDecimal experienceMod,
    BigDecimal scheduleMod,
    BigDecimal discountPct,
    BigDecimal surchargeAmt,
    BigDecimal taxAmt,
    BigDecimal totalPremium,
    String installmentCode,
    BigDecimal installmentAmt,
    LocalDate calcDate,
    String calcBy) {

  public Optional<InstallmentCode> installment() {
    return CopybookCode.lookup(InstallmentCode.class, installmentCode);
  }

  public static PremiumRecord fromBytes(byte[] record, Charset charset) {
    RecordValues v = PasCopybooks.PREMIUM_RECORD.decode(record, charset);
    return new PremiumRecord(
        v.string("PREM-POLICY-NUMBER"),
        v.integer("PREM-COVERAGE-SEQ"),
        v.date("PREM-TERM-EFF-DATE"),
        v.date("PREM-TERM-EXP-DATE"),
        v.decimal("PREM-BASE-RATE"),
        v.decimal("PREM-TERRITORY-FACTOR"),
        v.decimal("PREM-CLASS-FACTOR"),
        v.decimal("PREM-EXPERIENCE-MOD"),
        v.decimal("PREM-SCHEDULE-MOD"),
        v.decimal("PREM-DISCOUNT-PCT"),
        v.decimal("PREM-SURCHARGE-AMT"),
        v.decimal("PREM-TAX-AMT"),
        v.decimal("PREM-TOTAL-PREMIUM"),
        v.string("PREM-INSTALLMENT-CODE"),
        v.decimal("PREM-INSTALLMENT-AMT"),
        v.date("PREM-CALC-DATE"),
        v.string("PREM-CALC-BY"));
  }

  public byte[] toBytes(Charset charset) {
    RecordValues v =
        new RecordValues()
            .put("PREM-POLICY-NUMBER", policyNumber)
            .put("PREM-COVERAGE-SEQ", whole(coverageSeq))
            .put("PREM-TERM-EFF-DATE", termEffectiveDate)
            .put("PREM-TERM-EXP-DATE", termExpiryDate)
            .put("PREM-BASE-RATE", baseRate)
            .put("PREM-TERRITORY-FACTOR", territoryFactor)
            .put("PREM-CLASS-FACTOR", classFactor)
            .put("PREM-EXPERIENCE-MOD", experienceMod)
            .put("PREM-SCHEDULE-MOD", scheduleMod)
            .put("PREM-DISCOUNT-PCT", discountPct)
            .put("PREM-SURCHARGE-AMT", surchargeAmt)
            .put("PREM-TAX-AMT", taxAmt)
            .put("PREM-TOTAL-PREMIUM", totalPremium)
            .put("PREM-INSTALLMENT-CODE", installmentCode)
            .put("PREM-INSTALLMENT-AMT", installmentAmt)
            .put("PREM-CALC-DATE", calcDate)
            .put("PREM-CALC-BY", calcBy);
    return PasCopybooks.PREMIUM_RECORD.encode(v, charset);
  }
}
