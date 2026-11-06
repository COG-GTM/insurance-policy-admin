package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.decimal;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** ACMEINS.PREMIUMS row and PREMIUM-RECORD copybook. Rates and factors keep 4 decimal places. */
public record Premium(
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

  public Premium {
    text(required(policyNumber, "policyNumber"), 12, "policyNumber");
    baseRate = decimal(baseRate, 11, 4, "baseRate");
    territoryFactor = decimal(territoryFactor, 7, 4, "territoryFactor");
    classFactor = decimal(classFactor, 7, 4, "classFactor");
    experienceMod = decimal(experienceMod, 7, 4, "experienceMod");
    scheduleMod = decimal(scheduleMod, 7, 4, "scheduleMod");
    discountPct = decimal(discountPct, 5, 2, "discountPct");
    surchargeAmt = decimal(surchargeAmt, 9, 2, "surchargeAmt");
    taxAmt = decimal(taxAmt, 9, 2, "taxAmt");
    totalPremium = decimal(totalPremium, 11, 2, "totalPremium");
    text(installmentCode, 2, "installmentCode");
    installmentAmt = decimal(installmentAmt, 9, 2, "installmentAmt");
    text(calcBy, 8, "calcBy");
  }

  public Optional<InstallmentCode> installment() {
    return InstallmentCode.fromCode(installmentCode);
  }
}
