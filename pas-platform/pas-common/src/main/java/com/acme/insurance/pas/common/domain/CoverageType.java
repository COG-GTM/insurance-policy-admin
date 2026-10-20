package com.acme.insurance.pas.common.domain;

/** COVERAGE-RECORD COV-TYPE-CODE (PIC X(04)). */
public enum CoverageType implements CopybookCode {
  PROPERTY("PROP", "COV-PROPERTY"),
  LIABILITY("LIAB", "COV-LIABILITY"),
  AUTO_PHYSICAL("AUTP", "COV-AUTO-PHYS"),
  AUTO_LIABILITY("AUTL", "COV-AUTO-LIAB"),
  WORKERS_COMP("WKCP", "COV-WORKERS-COMP"),
  UMBRELLA("UMBR", "COV-UMBRELLA"),
  CYBER("CYBR", "COV-CYBER"),
  TERRORISM("TERR", "COV-TERRORISM");

  private final String code;
  private final String conditionName;

  CoverageType(String code, String conditionName) {
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
