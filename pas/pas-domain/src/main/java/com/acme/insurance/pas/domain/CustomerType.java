package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of CUST-TYPE / POLICY_HOLDERS.CUST_TYPE. */
public enum CustomerType implements LegacyCode {
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

  public static Optional<CustomerType> fromCode(String code) {
    return LegacyCode.decode(CustomerType.class, code);
  }
}
