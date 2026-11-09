package com.acme.insurance.pas.underwriting.web;

import java.time.LocalDate;

/**
 * Result of a PUWR transaction.
 *
 * @param decisionCode    WS-DECISION (AP / RS / RM / DC), also POLICY-UW-STATUS
 * @param decisionReason  WS-DECISION-REASON as written to UNDERWRITING_DECISIONS
 * @param riskScore       WS-RISK-SCORE PIC 9(04), as written to UNDERWRITING_DECISIONS and POLICIES
 * @param policyRiskScore POLICY-RISK-SCORE PIC 9(03), as shown on the UWRTRES map and sent on the queue
 */
public record DecisionResponse(
        String transactionId,
        String policyNumber,
        String decisionCode,
        String decisionReason,
        int riskScore,
        int policyRiskScore,
        LocalDate decisionDate) {
}
