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

  /** POLRNW 2000-VALIDATE-POLICY: only active and expired policies may be renewed. */
  public boolean renewable() {
    return this == ACTIVE || this == EXPIRED;
  }

  /** POLEND 2000-VALIDATE-POLICY: only active policies may be endorsed. */
  public boolean endorsable() {
    return this == ACTIVE;
  }

  public static Optional<PolicyStatus> fromCode(String code) {
    return LegacyCode.decode(PolicyStatus.class, code);
  }
}
