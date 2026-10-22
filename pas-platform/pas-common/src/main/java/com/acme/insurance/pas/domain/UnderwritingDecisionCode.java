package com.acme.insurance.pas.domain;

public enum UnderwritingDecisionCode implements CobolCode {
  APPROVED("AP"),
  REFERRED("RS"),
  MANUAL_REVIEW("RM"),
  DECLINED("DC");

  private final String code;

  UnderwritingDecisionCode(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
