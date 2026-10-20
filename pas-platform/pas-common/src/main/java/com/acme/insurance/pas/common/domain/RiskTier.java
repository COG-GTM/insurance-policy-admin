package com.acme.insurance.pas.common.domain;

/** CUSTOMER-RECORD CUST-RISK-TIER (PIC X(01)). */
public enum RiskTier implements CopybookCode {
  PREFERRED("P", "CUST-TIER-PREFERRED"),
  STANDARD("S", "CUST-TIER-STANDARD"),
  SUBSTANDARD("U", "CUST-TIER-SUBSTAND");

  private final String code;
  private final String conditionName;

  RiskTier(String code, String conditionName) {
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
