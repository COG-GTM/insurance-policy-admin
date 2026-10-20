package com.acme.insurance.pas.policy.underwriting;

import java.util.Optional;

/**
 * UNDWRT {@code WS-DECISION} values with the reason text {@code 5000-RENDER-DECISION} writes. RS
 * and RM have no 88-level in POLICY-RECORD (quirk register Q-21); codes are preserved as-is.
 */
public enum UnderwritingDecisionCode {
  ACCEPT("AP", "AUTO-ACCEPTED: LOW RISK"),
  REFER_SENIOR("RS", "REFERRED TO SENIOR UNDERWRITER"),
  REFER_MANAGER("RM", "REFERRED TO UW MANAGER"),
  DECLINE("DC", "AUTO-DECLINED: HIGH RISK");

  private final String code;
  private final String reason;

  UnderwritingDecisionCode(String code, String reason) {
    this.code = code;
    this.reason = reason;
  }

  public String code() {
    return code;
  }

  /** Reason text UNDWRT stores for an automated decision. */
  public String reason() {
    return reason;
  }

  public boolean isReferral() {
    return this == REFER_SENIOR || this == REFER_MANAGER;
  }

  public static Optional<UnderwritingDecisionCode> fromCode(String code) {
    if (code == null) {
      return Optional.empty();
    }
    String trimmed = code.strip();
    for (UnderwritingDecisionCode c : values()) {
      if (c.code.equals(trimmed)) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }
}
