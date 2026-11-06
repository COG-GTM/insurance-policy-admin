package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of CUST-RISK-TIER / POLICY_HOLDERS.RISK_TIER. */
public enum RiskTier implements LegacyCode {
  PREFERRED("P"),
  STANDARD("S"),
  SUBSTANDARD("U");

  private final String code;

  RiskTier(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }

  public static Optional<RiskTier> fromCode(String code) {
    return LegacyCode.decode(RiskTier.class, code);
  }
}
