package com.acme.insurance.pas.common.domain;

/** POLICY-RECORD POLICY-TYPE (PIC X(03)). */
public enum PolicyType implements CopybookCode {
  AUTO("AUT", "POL-TYPE-AUTO"),
  HOME("HOM", "POL-TYPE-HOME"),
  COMMERCIAL("COM", "POL-TYPE-COMM"),
  LIFE("LIF", "POL-TYPE-LIFE"),
  HEALTH("HLT", "POL-TYPE-HEALTH");

  private final String code;
  private final String conditionName;

  PolicyType(String code, String conditionName) {
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
