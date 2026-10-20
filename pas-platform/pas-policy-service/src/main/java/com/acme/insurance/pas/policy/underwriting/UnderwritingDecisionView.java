package com.acme.insurance.pas.policy.underwriting;

import com.acme.insurance.pas.policy.persistence.UnderwritingDecisionEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One {@code pas.underwriting_decisions} audit row. {@code created_timestamp} is stored as UTC
 * ({@code BusinessCalendar#nowUtc}) and returned with an explicit {@code Z} offset.
 */
public record UnderwritingDecisionView(
    long decisionId,
    String policyNumber,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate decisionDate,
    String decisionCode,
    Integer riskScore,
    String decisionReason,
    String underwriterId,
    boolean override,
    String overrideReason,
    String overrideBy,
    OffsetDateTime createdTimestamp) {

  static UnderwritingDecisionView from(UnderwritingDecisionEntity d) {
    return new UnderwritingDecisionView(
        d.getDecisionId(),
        trim(d.getPolicyNumber()),
        d.getDecisionDate(),
        trim(d.getDecisionCode()),
        d.getRiskScore() == null ? null : d.getRiskScore().intValue(),
        d.getDecisionReason(),
        trim(d.getUnderwriterId()),
        d.getOverrideBy() != null,
        d.getOverrideReason(),
        trim(d.getOverrideBy()),
        d.getCreatedTimestamp() == null ? null : d.getCreatedTimestamp().atOffset(ZoneOffset.UTC));
  }

  private static String trim(String value) {
    return value == null ? null : value.strip();
  }
}
