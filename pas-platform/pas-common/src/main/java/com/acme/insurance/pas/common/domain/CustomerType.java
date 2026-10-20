package com.acme.insurance.pas.common.domain;

/** CUSTOMER-RECORD CUST-TYPE (PIC X(01)). */
public enum CustomerType implements CopybookCode {
  INDIVIDUAL("I", "CUST-INDIVIDUAL"),
  COMMERCIAL("C", "CUST-COMMERCIAL"),
  GOVERNMENT("G", "CUST-GOVERNMENT");

  private final String code;
  private final String conditionName;

  CustomerType(String code, String conditionName) {
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
