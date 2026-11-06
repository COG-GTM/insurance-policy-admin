package com.acme.insurance.pas.underwriting;

import com.acme.insurance.pas.domain.UnderwritingStatus;

/**
 * The four outcomes of UNDWRT {@code 5000-RENDER-DECISION}, with the code written to
 * UW_STATUS/DECISION_CODE (RS and RM sit outside the copybook domain, quirk Q-17) and the reason
 * text.
 */
public enum DecisionBand {
  ACCEPT(UnderwritingStatus.APPROVED, "AUTO-ACCEPTED: LOW RISK"),
  REFER_SENIOR(UnderwritingStatus.REFERRED_SENIOR, "REFERRED TO SENIOR UNDERWRITER"),
  REFER_MANAGER(UnderwritingStatus.REFERRED_MANAGER, "REFERRED TO UW MANAGER"),
  DECLINE(UnderwritingStatus.DECLINED, "AUTO-DECLINED: HIGH RISK");

  private final UnderwritingStatus status;
  private final String reason;

  DecisionBand(UnderwritingStatus status, String reason) {
    this.status = status;
    this.reason = reason;
  }

  public UnderwritingStatus status() {
    return status;
  }

  public String reason() {
    return reason;
  }

  /** RS and RM: the policy waits for a person. */
  public boolean isReferral() {
    return this == REFER_SENIOR || this == REFER_MANAGER;
  }
}
