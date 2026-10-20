package com.acme.insurance.pas.common.domain;

/** PREMIUM-RECORD PREM-INSTALLMENT-CODE (PIC X(02)). */
public enum InstallmentCode implements CopybookCode {
  ANNUAL("AN", "PREM-ANNUAL"),
  SEMI_ANNUAL("SA", "PREM-SEMI-ANNUAL"),
  QUARTERLY("QT", "PREM-QUARTERLY"),
  MONTHLY("MO", "PREM-MONTHLY");

  private final String code;
  private final String conditionName;

  InstallmentCode(String code, String conditionName) {
    this.code = code;
    this.conditionName = conditionName;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String conditionName() {
    return conditionName;
  }
}
