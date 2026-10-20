package com.acme.insurance.pas.common.domain;

/** COVERAGE-RECORD COV-STATUS (PIC X(02)). */
public enum CoverageStatus implements CopybookCode {
  ACTIVE("AC", "COV-STAT-ACTIVE"),
  PENDING("PN", "COV-STAT-PENDING"),
  CANCELLED("CN", "COV-STAT-CANCELLED");

  private final String code;
  private final String conditionName;

  CoverageStatus(String code, String conditionName) {
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
