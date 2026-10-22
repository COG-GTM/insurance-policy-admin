package com.acme.insurance.pas.domain;

public enum PolicyType implements CobolCode {
  AUTO("AUT"),
  HOME("HOM"),
  COMM("COM"),
  LIFE("LIF"),
  HEALTH("HLT");

  private final String code;

  PolicyType(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
