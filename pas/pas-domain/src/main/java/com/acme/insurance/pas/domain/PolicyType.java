package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of POLICY-TYPE / POLICIES.POLICY_TYPE. */
public enum PolicyType implements LegacyCode {
  AUTO("AUT"),
  HOME("HOM"),
  COMMERCIAL("COM"),
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

  public static Optional<PolicyType> fromCode(String code) {
    return LegacyCode.decode(PolicyType.class, code);
  }
}
