package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of PREM-INSTALLMENT-CODE / PREMIUMS.INSTALLMENT_CODE. */
public enum InstallmentCode implements LegacyCode {
  ANNUAL("AN"),
  SEMI_ANNUAL("SA"),
  QUARTERLY("QT"),
  MONTHLY("MO");

  private final String code;

  InstallmentCode(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }

  public static Optional<InstallmentCode> fromCode(String code) {
    return LegacyCode.decode(InstallmentCode.class, code);
  }
}
