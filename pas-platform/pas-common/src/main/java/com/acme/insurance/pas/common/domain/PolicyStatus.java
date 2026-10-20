package com.acme.insurance.pas.common.domain;

/** POLICY-RECORD POLICY-STATUS (PIC X(02)). */
public enum PolicyStatus implements CopybookCode {
  ACTIVE("AC", "POL-STAT-ACTIVE"),
  PENDING("PN", "POL-STAT-PENDING"),
  CANCELLED("CN", "POL-STAT-CANCELLED"),
  EXPIRED("EX", "POL-STAT-EXPIRED"),
  LAPSED("LP", "POL-STAT-LAPSED");

  private final String code;
  private final String conditionName;

  PolicyStatus(String code, String conditionName) {
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
