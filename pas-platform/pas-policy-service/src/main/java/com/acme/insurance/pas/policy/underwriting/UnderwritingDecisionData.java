package com.acme.insurance.pas.policy.underwriting;

import java.time.LocalDate;

/**
 * {@code UnderwritingDecided} / {@code UnderwritingReferred} event data (replaces UNDWRT's TDQ
 * write to {@code ACME.PAS.UNDERWRITING.DECISION}). The override reason is free text and stays out
 * of the event; consumers read it through the API.
 */
public record UnderwritingDecisionData(
    long decisionId,
    LocalDate decisionDate,
    String decisionCode,
    Integer riskScore,
    Integer policyRiskScore,
    String decisionReason,
    String underwriterId,
    boolean override,
    String overrideBy) {}
