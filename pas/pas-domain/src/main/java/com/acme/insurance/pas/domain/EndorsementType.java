package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of ENDORSEMENTS.ENDORSEMENT_TYPE (POLEND 88-levels). */
public enum EndorsementType implements LegacyCode {
  COVERAGE_ADD("CAD"),
  COVERAGE_REMOVE("CRM"),
  LIMIT_CHANGE("LCH"),
  ADDRESS_CHANGE("ACH"),
  CANCELLATION("CAN");

  private final String code;

  EndorsementType(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }

  public static Optional<EndorsementType> fromCode(String code) {
    return LegacyCode.decode(EndorsementType.class, code);
  }
}
