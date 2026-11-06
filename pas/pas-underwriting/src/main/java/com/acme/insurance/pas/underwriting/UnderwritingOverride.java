package com.acme.insurance.pas.underwriting;

import com.acme.insurance.pas.domain.UnderwritingDecision;
import com.acme.insurance.pas.domain.UnderwritingStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A manual override of the latest underwriting decision (OVERRIDE_REASON / OVERRIDE_BY, columns the
 * COBOL never writes). An underwriter accepts or declines; the override is stored on the decision
 * row it overrides, the policy's UW_STATUS follows, and the row before the change goes to the audit
 * table.
 *
 * @param decisionCode AP or DC
 * @param reason why, at most 200 characters
 * @param overrideBy 8-character user id for OVERRIDE_BY (quirk Q-518-1)
 */
public record UnderwritingOverride(String decisionCode, String reason, String overrideBy) {

  /** Decisions an underwriter can set by override. */
  public static final Set<String> ALLOWED_CODES =
      Set.of(UnderwritingStatus.APPROVED.code(), UnderwritingStatus.DECLINED.code());

  static final int REASON_LENGTH = 200;
  static final int USER_LENGTH = 8;

  /** Problems with this override, empty when it may be applied. */
  public List<String> violations() {
    List<String> problems = new ArrayList<>();
    if (decisionCode == null || !ALLOWED_CODES.contains(decisionCode)) {
      problems.add("decisionCode must be one of " + new java.util.TreeSet<>(ALLOWED_CODES));
    }
    if (reason == null || reason.isBlank()) {
      problems.add("reason is required");
    } else if (reason.length() > REASON_LENGTH) {
      problems.add("reason must be at most " + REASON_LENGTH + " characters");
    }
    if (overrideBy == null || overrideBy.isBlank() || overrideBy.length() > USER_LENGTH) {
      problems.add("overrideBy must be 1 to " + USER_LENGTH + " characters");
    }
    return problems;
  }

  /** The decision row after the override. */
  public UnderwritingDecision applyTo(UnderwritingDecision latest) {
    Objects.requireNonNull(latest, "latest");
    if (!violations().isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", violations()));
    }
    return new UnderwritingDecision(
        latest.decisionId(),
        latest.policyNumber(),
        latest.decisionDate(),
        decisionCode,
        latest.riskScore(),
        latest.decisionReason(),
        latest.underwriterId(),
        reason,
        overrideBy,
        latest.createdTimestamp());
  }
}
