package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of POLICY-STATUS / POLICIES.POLICY_STATUS. */
public enum PolicyStatus implements LegacyCode {
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

  public static Optional<PolicyStatus> fromCode(String code) {
    return LegacyCode.decode(PolicyStatus.class, code);
  }
}
