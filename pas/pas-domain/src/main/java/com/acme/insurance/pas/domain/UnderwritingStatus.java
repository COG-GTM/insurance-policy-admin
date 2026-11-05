package com.acme.insurance.pas.domain;

import java.util.Optional;

/**
 * Known values of POLICY-UW-STATUS / POLICIES.UW_STATUS and UNDERWRITING_DECISIONS.DECISION_CODE.
 *
 * <p>The copybook 88-levels define AP, PN, DC and RF, but UNDWRT writes RS and RM (quirk Q-17), so
 * both are modelled. {@link #inCopybookDomain()} tells the two groups apart.
 */
public enum UnderwritingStatus implements LegacyCode {
  APPROVED("AP", true),
  PENDING("PN", true),
  DECLINED("DC", true),
  REFERRED("RF", true),
  REFERRED_SENIOR("RS", false),
  REFERRED_MANAGER("RM", false);

  private final String code;
  private final boolean inCopybookDomain;

  UnderwritingStatus(String code, boolean inCopybookDomain) {
    this.code = code;
    this.inCopybookDomain = inCopybookDomain;
  }

  @Override
  public String code() {
    return code;
  }

  /** True when the value is one of the POLICY-RECORD 88-levels. */
  public boolean inCopybookDomain() {
    return inCopybookDomain;
  }

  public static Optional<UnderwritingStatus> fromCode(String code) {
    return LegacyCode.decode(UnderwritingStatus.class, code);
  }
}
