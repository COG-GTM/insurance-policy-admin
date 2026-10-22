package com.acme.insurance.pas.domain;

public enum InstallmentCode implements CobolCode {
  ANNUAL("AN"),
  SEMI_ANNUAL("SA"),
  QUARTERLY("QT"),
  MONTHLY("MO");

  private final String code;

  InstallmentCode(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
