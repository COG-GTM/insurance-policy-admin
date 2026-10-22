package com.acme.insurance.pas.domain;

public enum PolicyStatus implements CobolCode {
  ACTIVE("AC"),
  PENDING("PN"),
  CANCELLED("CN"),
  EXPIRED("EX"),
  LAPSED("LP");

  private final String code;

  PolicyStatus(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
