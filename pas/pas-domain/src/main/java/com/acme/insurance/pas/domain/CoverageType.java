package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of COV-TYPE-CODE / COVERAGES.COVERAGE_TYPE. */
public enum CoverageType implements LegacyCode {
  PROPERTY("PROP"),
  LIABILITY("LIAB"),
  AUTO_PHYSICAL_DAMAGE("AUTP"),
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

  public static Optional<CoverageType> fromCode(String code) {
    return LegacyCode.decode(CoverageType.class, code);
  }
}
