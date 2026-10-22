package com.acme.insurance.pas.domain;

public enum CoverageType implements CobolCode {
  PROPERTY("PROP"),
  LIABILITY("LIAB"),
  AUTO_PHYSICAL("AUTP"),
  AUTO_LIABILITY("AUTL"),
  WORKERS_COMP("WKCP"),
  UMBRELLA("UMBR"),
  CYBER("CYBR"),
  TERRORISM("TERR");

  private final String code;

  CoverageType(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
