package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of COV-STATUS / COVERAGES.STATUS. */
public enum CoverageStatus implements LegacyCode {
  ACTIVE("AC"),
  PENDING("PN"),
  CANCELLED("CN");

  private final String code;

  CoverageStatus(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }

  public static Optional<CoverageStatus> fromCode(String code) {
    return LegacyCode.decode(CoverageStatus.class, code);
  }
}
