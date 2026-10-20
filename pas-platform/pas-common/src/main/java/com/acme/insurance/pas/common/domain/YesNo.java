package com.acme.insurance.pas.common.domain;

/** Y/N flags: POL-WEB-YES/NO, POL-API-YES/NO, CUST-GDPR-YES/NO (PIC X(01)). */
public enum YesNo implements CopybookCode {
  YES("Y", "*-YES"),
  NO("N", "*-NO");

  private final String code;
  private final String conditionName;

  YesNo(String code, String conditionName) {
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
