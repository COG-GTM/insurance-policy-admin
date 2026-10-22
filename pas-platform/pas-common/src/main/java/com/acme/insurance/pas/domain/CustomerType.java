package com.acme.insurance.pas.domain;

public enum CustomerType implements CobolCode {
  INDIVIDUAL("I"),
  COMMERCIAL("C"),
  GOVERNMENT("G");

  private final String code;

  CustomerType(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
