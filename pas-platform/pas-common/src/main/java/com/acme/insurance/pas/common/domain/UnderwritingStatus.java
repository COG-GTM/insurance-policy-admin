package com.acme.insurance.pas.common.domain;

/** POLICY-RECORD POLICY-UW-STATUS (PIC X(02)). */
public enum UnderwritingStatus implements CopybookCode {
  APPROVED("AP", "UW-APPROVED"),
  PENDING("PN", "UW-PENDING"),
  DECLINED("DC", "UW-DECLINED"),
  REFERRED("RF", "UW-REFERRED");

  private final String code;
  private final String conditionName;

  UnderwritingStatus(String code, String conditionName) {
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
