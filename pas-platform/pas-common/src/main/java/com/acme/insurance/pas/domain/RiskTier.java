package com.acme.insurance.pas.domain;

public enum RiskTier implements CobolCode {
  PREFERRED("P"),
  STANDARD("S"),
  SUBSTANDARD("U");

  private final String code;

  RiskTier(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
