package com.acme.insurance.pas.underwriting.decision;

import com.acme.insurance.pas.underwriting.copybook.PolicyRecord;
import java.time.LocalDate;

/** Outcome of one PUWR transaction: either 8000-DISPLAY-RESULT or 9000-SEND-ERROR. */
public sealed interface UnderwritingResult {

    /**
     * 8000-DISPLAY-RESULT.
     *
     * @param riskScore      WS-RISK-SCORE PIC 9(04), as written to UNDERWRITING_DECISIONS / POLICIES
     * @param decisionReason WS-DECISION-REASON PIC X(100), right-trimmed
     * @param policyRecord   POLICY-RECORD sent on the UWRTRES map and the TD queue
     *                       (POLICY-RISK-SCORE PIC 9(03) holds the low-order 3 digits of the score)
     */
    record Decision(DecisionCode decisionCode, int riskScore, String decisionReason, LocalDate decisionDate,
            PolicyRecord policyRecord) implements UnderwritingResult {
    }

    /** 9000-SEND-ERROR: WS-ERROR-MSG PIC X(79). */
    record Error(Kind kind, String errorMessage) implements UnderwritingResult {

        public enum Kind {
            /** EXEC CICS RECEIVE MAP did not return DFHRESP(NORMAL). */
            RECEIVE,
            /** SELECT in 2000-READ-POLICY-DATA returned SQLCODE NOT = 0. */
            NOT_FOUND
        }
    }
}
