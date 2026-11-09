package com.acme.insurance.pas.underwriting.decision;

import java.math.BigDecimal;

/**
 * Literals hard-coded in UNDWRT.cbl. Kept as constants (not configuration) so behaviour is
 * identical to the COBOL program; see the TODO at UNDWRT.cbl line 179.
 */
public final class UnderwritingRules {

    private UnderwritingRules() {
    }

    public static final String PROGRAM_ID = "UNDWRT";
    public static final String TRANSACTION_ID = "PUWR";
    public static final String UNDERWRITER_ID = "SYSTEM";
    public static final String MQ_QUEUE = "ACME.PAS.UNDERWRITING.DECISION";

    /** WS-ERROR-MSG text set in 2000-READ-POLICY-DATA when EXEC CICS RECEIVE fails. */
    public static final String MSG_RECEIVE_ERROR = "ERROR RECEIVING UNDERWRITING DATA";
    /** WS-ERROR-MSG text set in 2000-READ-POLICY-DATA when SQLCODE NOT = 0. */
    public static final String MSG_NOT_FOUND = "POLICY/CUSTOMER DATA NOT FOUND";
    /** STRING literal in 4000-CHECK-ACCUMULATION (overwritten by 5000-RENDER-DECISION). */
    public static final String MSG_ACCUMULATION_PREFIX = "ACCUMULATION LIMIT EXCEEDED FOR BRANCH ";

    /** 01 WS-RISK-SCORE PIC 9(04). */
    public static final int RISK_SCORE_DIGITS = 4;
    /** 01 WS-LOSS-RATIO PIC S9(03)V99 COMP-3. */
    public static final int LOSS_RATIO_INTEGER_DIGITS = 3;
    public static final int LOSS_RATIO_SCALE = 2;
    /** 01 WS-CLAIM-COUNT PIC 9(05). */
    public static final int CLAIM_COUNT_DIGITS = 5;
    /** 01 WS-DECISION-REASON PIC X(100). */
    public static final int DECISION_REASON_LENGTH = 100;
    /** 01 WS-ERROR-MSG PIC X(79), sent with LENGTH(79). */
    public static final int ERROR_MSG_LENGTH = 79;

    public static final int CREDIT_BASE = 900;
    public static final int NO_CREDIT_SCORE = 400;
    public static final int SUBSTANDARD_ADD = 200;
    public static final int PREFERRED_SUBTRACT = 100;
    public static final int LOSS_HISTORY_YEARS = 5;
    public static final int CLAIMS_TIER1_COUNT = 3;
    public static final int CLAIMS_TIER1_ADD = 150;
    public static final int CLAIMS_TIER2_COUNT = 5;
    public static final int CLAIMS_TIER2_ADD = 200;
    public static final BigDecimal LOSS_RATIO_THRESHOLD = new BigDecimal("80");
    public static final int LOSS_RATIO_ADD = 200;
    public static final BigDecimal HIGH_LIMIT_TIER1 = new BigDecimal("5000000");
    public static final int HIGH_LIMIT_TIER1_ADD = 100;
    public static final BigDecimal HIGH_LIMIT_TIER2 = new BigDecimal("10000000");
    public static final int HIGH_LIMIT_TIER2_ADD = 200;
    public static final String ACCUMULATION_POLICY_STATUS = "AC";
    public static final BigDecimal ACCUMULATION_LIMIT = new BigDecimal("500000000");
    public static final int ACCUMULATION_ADD = 300;

    public static final int ACCEPT_BELOW = 300;
    public static final int REFER_SENIOR_BELOW = 600;
    public static final int REFER_MANAGER_BELOW = 800;
}
