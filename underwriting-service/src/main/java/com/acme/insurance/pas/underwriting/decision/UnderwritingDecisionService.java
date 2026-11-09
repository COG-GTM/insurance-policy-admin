package com.acme.insurance.pas.underwriting.decision;

import static com.acme.insurance.pas.underwriting.decision.UnderwritingRules.*;

import com.acme.insurance.pas.underwriting.cobol.CobolArithmetic;
import com.acme.insurance.pas.underwriting.copybook.CopybookFields;
import com.acme.insurance.pas.underwriting.copybook.CustomerRecord;
import com.acme.insurance.pas.underwriting.copybook.PolicyRecord;
import com.acme.insurance.pas.underwriting.messaging.UnderwritingNotificationPublisher;
import com.acme.insurance.pas.underwriting.persistence.ClaimsHistory;
import com.acme.insurance.pas.underwriting.persistence.PolicyCustomerRow;
import com.acme.insurance.pas.underwriting.persistence.UnderwritingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of UNDWRT.cbl (CICS transaction PUWR). Each method is named after, and does exactly what,
 * the COBOL paragraph of the same name does. See docs/UNDWRT-migration.md.
 */
@Service
public class UnderwritingDecisionService {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingDecisionService.class);

    private final UnderwritingRepository repository;
    private final UnderwritingNotificationPublisher publisher;
    private final Clock clock;

    public UnderwritingDecisionService(UnderwritingRepository repository,
            UnderwritingNotificationPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** WORKING-STORAGE of one task; CICS gives every PUWR task a fresh copy. */
    static final class WorkArea {
        String errorMsg = "";
        LocalDate currentDate;
        int riskScore;
        DecisionCode decision;
        String decisionReason = "";
        BigDecimal lossRatio;
        long claimCount;
        BigDecimal totalIncurred;
        BigDecimal accumLimit;
        PolicyRecord policy;
        CustomerRecord customer;
    }

    /** 0000-MAIN-LOGIC. The CICS unit of work is committed at 9999-RETURN (end of this method). */
    @Transactional(noRollbackFor = DataAccessException.class)
    public UnderwritingResult decide(String policyNumberInput) {
        WorkArea ws = new WorkArea();
        initialize(ws);
        readPolicyData(ws, policyNumberInput);
        if (ws.errorMsg.isEmpty()) {
            calculateRiskScore(ws);
            checkAccumulation(ws);
            renderDecision(ws);
            writeDecision(ws);
            sendMqNotification(ws);
            return displayResult(ws);
        }
        return sendError(ws);
    }

    /** 1000-INITIALIZE. */
    void initialize(WorkArea ws) {
        ws.errorMsg = "";
        ws.decisionReason = "";
        ws.riskScore = 0;
        ws.currentDate = LocalDate.now(clock);
    }

    /** 2000-READ-POLICY-DATA (RECEIVE MAP UWRTMAP, then SELECT POLICIES JOIN POLICY_HOLDERS). */
    void readPolicyData(WorkArea ws, String policyNumberInput) {
        String policyNumber = receiveMap(policyNumberInput);
        if (policyNumber == null) {
            ws.errorMsg = MSG_RECEIVE_ERROR;
            return;
        }
        List<PolicyCustomerRow> rows = repository.selectPolicyAndCustomer(pad(policyNumber, 12));
        // SQLCODE +100 (no row), -811 (more than one row), -305 (NULL without indicator)
        // and -304 (value does not fit the host variable) all fail the IF SQLCODE NOT = 0 test.
        if (rows.size() != 1 || rows.get(0).hasNullColumn() || !fitsUnsigned(rows.get(0).creditScore(),
                CustomerRecord.CREDIT_SCORE_DIGITS)) {
            ws.errorMsg = MSG_NOT_FOUND;
            return;
        }
        PolicyCustomerRow row = rows.get(0);
        ws.policy = PolicyRecord.fromUnderwritingSelect(row.policyNumber(), row.policyType(),
                row.totalPremium(), row.coverageLimit(), row.branchCode());
        ws.customer = CustomerRecord.fromUnderwritingSelect(row.custType(), row.creditScore(), row.riskTier());
    }

    /**
     * EXEC CICS RECEIVE MAP('UWRTMAP') INTO(POLICY-RECORD): the only input field UNDWRT uses is
     * POLICY-NUMBER PIC X(12). No data (MAPFAIL) or a value that cannot fit the field is a
     * non-NORMAL response. Returns the right-trimmed policy number, or null on failure.
     */
    static String receiveMap(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.strip();
        if (trimmed.isEmpty() || trimmed.length() > PolicyRecord.POLICY_NUMBER_LENGTH) {
            return null;
        }
        return CopybookFields.alnum("POLICY-NUMBER", trimmed, PolicyRecord.POLICY_NUMBER_LENGTH);
    }

    /** 3000-CALCULATE-RISK-SCORE. */
    void calculateRiskScore(WorkArea ws) {
        int creditScore = ws.customer.creditScore();
        // Base score from credit: COMPUTE WS-RISK-SCORE = (900 - CUST-CREDIT-SCORE) / 2
        if (creditScore > 0) {
            ws.riskScore = CobolArithmetic.storeUnsigned(
                    CobolArithmetic.divideTruncated(CREDIT_BASE - creditScore, 2), RISK_SCORE_DIGITS);
        } else {
            ws.riskScore = NO_CREDIT_SCORE;
        }

        // Risk tier adjustment
        if (ws.customer.isTierSubstandard()) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + SUBSTANDARD_ADD, RISK_SCORE_DIGITS);
        }
        if (ws.customer.isTierPreferred()) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore - PREFERRED_SUBTRACT, RISK_SCORE_DIGITS);
        }

        // Loss history check: CLAIM_DATE >= CURRENT DATE - 5 YEARS
        ClaimsHistory claims = repository.selectClaimsHistory(pad(ws.policy.policyNumber(), 12),
                ws.currentDate.minusYears(LOSS_HISTORY_YEARS));
        ws.claimCount = CobolArithmetic.storeUnsigned(claims.claimCount(), CLAIM_COUNT_DIGITS);
        ws.totalIncurred = claims.totalIncurred();
        if (ws.claimCount > CLAIMS_TIER1_COUNT) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + CLAIMS_TIER1_ADD, RISK_SCORE_DIGITS);
        }
        if (ws.claimCount > CLAIMS_TIER2_COUNT) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + CLAIMS_TIER2_ADD, RISK_SCORE_DIGITS);
        }

        // Loss ratio check: COMPUTE WS-LOSS-RATIO = (WS-TOTAL-INCURRED / POLICY-TOTAL-PREMIUM) * 100
        BigDecimal premium = ws.policy.totalPremium();
        if (premium.signum() > 0) {
            BigDecimal quotient = CobolArithmetic.divideIntermediate(ws.totalIncurred, premium, LOSS_RATIO_SCALE);
            ws.lossRatio = CobolArithmetic.storeSigned(quotient.multiply(BigDecimal.valueOf(100)),
                    LOSS_RATIO_INTEGER_DIGITS, LOSS_RATIO_SCALE);
            if (ws.lossRatio.compareTo(LOSS_RATIO_THRESHOLD) > 0) {
                ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + LOSS_RATIO_ADD, RISK_SCORE_DIGITS);
            }
        }

        // High limit surcharge
        BigDecimal limit = ws.policy.limit();
        if (limit.compareTo(HIGH_LIMIT_TIER1) > 0) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + HIGH_LIMIT_TIER1_ADD, RISK_SCORE_DIGITS);
        }
        if (limit.compareTo(HIGH_LIMIT_TIER2) > 0) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + HIGH_LIMIT_TIER2_ADD, RISK_SCORE_DIGITS);
        }
    }

    /** 4000-CHECK-ACCUMULATION. */
    void checkAccumulation(WorkArea ws) {
        ws.accumLimit = repository.selectBranchAccumulation(pad(ws.policy.branchCode(), 4), ACCUMULATION_POLICY_STATUS);
        if (ws.accumLimit.compareTo(ACCUMULATION_LIMIT) > 0) {
            ws.riskScore = CobolArithmetic.storeUnsigned((long) ws.riskScore + ACCUMULATION_ADD, RISK_SCORE_DIGITS);
            // STRING ... DELIMITED BY SIZE INTO WS-DECISION-REASON (POLICY-BRANCH-CODE is PIC X(04)).
            ws.decisionReason = CopybookFields.alnum("WS-DECISION-REASON",
                    MSG_ACCUMULATION_PREFIX + pad(ws.policy.branchCode(), 4), DECISION_REASON_LENGTH);
        }
    }

    /** 5000-RENDER-DECISION. Unconditionally overwrites WS-DECISION-REASON, as the COBOL does. */
    void renderDecision(WorkArea ws) {
        if (ws.riskScore < ACCEPT_BELOW) {
            ws.decision = DecisionCode.AP;
        } else if (ws.riskScore < REFER_SENIOR_BELOW) {
            ws.decision = DecisionCode.RS;
        } else if (ws.riskScore < REFER_MANAGER_BELOW) {
            ws.decision = DecisionCode.RM;
        } else {
            ws.decision = DecisionCode.DC;
        }
        ws.decisionReason = ws.decision.reason();
        ws.policy = ws.policy.withDecision(ws.decision.name(),
                CobolArithmetic.moveUnsigned(ws.riskScore, PolicyRecord.RISK_SCORE_DIGITS));
    }

    /**
     * 6000-WRITE-DECISION. UNDWRT checks no SQLCODE here: a failed INSERT (e.g. -803 for a second
     * decision on the same day) or UPDATE does not stop the transaction, and whatever succeeded is
     * committed at RETURN.
     */
    void writeDecision(WorkArea ws) {
        String policyNumber = pad(ws.policy.policyNumber(), 12);
        try {
            repository.insertDecision(policyNumber, ws.currentDate, ws.decision.name(), ws.riskScore,
                    pad(ws.decisionReason, DECISION_REASON_LENGTH), UNDERWRITER_ID);
        } catch (DataAccessException e) {
            log.warn("INSERT UNDERWRITING_DECISIONS failed for {} (ignored, as in UNDWRT): {}",
                    ws.policy.policyNumber(), e.getMostSpecificCause().getMessage());
        }
        try {
            repository.updatePolicyDecision(policyNumber, ws.decision.name(), ws.riskScore, PROGRAM_ID);
        } catch (DataAccessException e) {
            log.warn("UPDATE POLICIES failed for {} (ignored, as in UNDWRT): {}",
                    ws.policy.policyNumber(), e.getMostSpecificCause().getMessage());
        }
    }

    /** 7000-SEND-MQ-NOTIFICATION. RESP is captured but never tested in UNDWRT. */
    void sendMqNotification(WorkArea ws) {
        try {
            publisher.publish(MQ_QUEUE, ws.policy);
        } catch (RuntimeException e) {
            log.warn("WRITEQ TD {} failed (ignored, as in UNDWRT): {}", MQ_QUEUE, e.getMessage());
        }
    }

    /** 8000-DISPLAY-RESULT. */
    UnderwritingResult displayResult(WorkArea ws) {
        return new UnderwritingResult.Decision(ws.decision, ws.riskScore, ws.decisionReason, ws.currentDate,
                ws.policy);
    }

    /** 9000-SEND-ERROR. */
    UnderwritingResult sendError(WorkArea ws) {
        UnderwritingResult.Error.Kind kind = MSG_RECEIVE_ERROR.equals(ws.errorMsg)
                ? UnderwritingResult.Error.Kind.RECEIVE
                : UnderwritingResult.Error.Kind.NOT_FOUND;
        return new UnderwritingResult.Error(kind, ws.errorMsg);
    }

    private static boolean fitsUnsigned(Integer value, int digits) {
        return value != null && value >= 0 && value < Math.pow(10, digits);
    }

    /** Blank-pad to the PIC X(n) length, as COBOL host variables are. */
    static String pad(String value, int length) {
        StringBuilder sb = new StringBuilder(value == null ? "" : value);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
