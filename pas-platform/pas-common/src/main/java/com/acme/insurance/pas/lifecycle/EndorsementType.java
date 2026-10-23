package com.acme.insurance.pas.lifecycle;

import com.acme.insurance.pas.lifecycle.LifecycleRejectedException.Rejection;
import java.util.Arrays;
import java.util.Locale;

/** POLEND WS-ENDORSEMENT-TYPE 88-levels, validated as required input (Q-12). */
public enum EndorsementType {
  COVERAGE_ADD("CAD"),
  COVERAGE_REMOVE("CRM"),
  LIMIT_CHANGE("LCH"),
  ADDRESS_CHANGE("ACH"),
  CANCEL("CAN");

  private final String code;

  EndorsementType(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static EndorsementType fromCode(String value) {
    if (value == null || value.isBlank()) {
      throw new LifecycleRejectedException(Rejection.ENDORSEMENT_TYPE_REQUIRED);
    }
    String code = value.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(type -> type.code.equals(code))
        .findFirst()
        .orElseThrow(() -> new LifecycleRejectedException(Rejection.ENDORSEMENT_TYPE_INVALID));
  }
}
