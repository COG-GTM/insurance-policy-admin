package com.acme.insurance.pas.domain;

public enum CoverageStatus implements CobolCode {
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
}
