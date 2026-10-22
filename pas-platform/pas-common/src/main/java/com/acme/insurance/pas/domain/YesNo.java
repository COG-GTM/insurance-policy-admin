package com.acme.insurance.pas.domain;

public enum YesNo implements CobolCode {
  YES("Y"),
  NO("N");

  private final String code;

  YesNo(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
