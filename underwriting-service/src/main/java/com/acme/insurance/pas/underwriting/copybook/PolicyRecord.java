package com.acme.insurance.pas.underwriting.copybook;

import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.alnum;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.date;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.packed;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.unsigned;

import java.math.BigDecimal;

/**
 * {@code POLICY-RECORD} from {@code cobol/copybooks/POLICY-RECORD.cpy} (DB2 table POLICIES).
 * Record length 125 bytes; the trailing {@code FILLER PIC X(20)} is not mapped.
 * Alphanumeric values are held right-trimmed (COBOL pads them with SPACES).
 *
 * @param policyNumber      POLICY-NUMBER PIC X(12), offset 0
 * @param policyType        POLICY-TYPE PIC X(03), offset 12 (88 AUT/HOM/COM/LIF/HLT)
 * @param policyStatus      POLICY-STATUS PIC X(02), offset 15 (88 AC/PN/CN/EX/LP)
 * @param effectiveDate     POLICY-EFFECTIVE-DATE PIC 9(08), offset 17
 * @param expiryDate        POLICY-EXPIRY-DATE PIC 9(08), offset 25
 * @param holderId          POLICY-HOLDER-ID PIC X(10), offset 33
 * @param agentCode         POLICY-AGENT-CODE PIC X(06), offset 43
 * @param branchCode        POLICY-BRANCH-CODE PIC X(04), offset 49
 * @param totalPremium      POLICY-TOTAL-PREMIUM PIC S9(09)V99 COMP-3, offset 53, 6 bytes
 * @param deductible        POLICY-DEDUCTIBLE PIC S9(07)V99 COMP-3, offset 59, 5 bytes
 * @param limit             POLICY-LIMIT PIC S9(11)V99 COMP-3, offset 64, 7 bytes
 * @param inceptionDate     POLICY-INCEPTION-DATE PIC 9(08), offset 71
 * @param renewalCount      POLICY-RENEWAL-COUNT PIC 9(03), offset 79
 * @param uwStatus          POLICY-UW-STATUS PIC X(02), offset 82 (UNDWRT writes AP/RS/RM/DC)
 * @param riskScore         POLICY-RISK-SCORE PIC 9(03), offset 84
 * @param webIndicator      POLICY-WEB-IND PIC X(01), offset 87
 * @param apiFlag           POLICY-API-FLAG PIC X(01), offset 88
 * @param lastUpdated       POLICY-LAST-UPDATED PIC 9(08), offset 89
 * @param updatedBy         POLICY-UPDATED-BY PIC X(08), offset 97
 */
public record PolicyRecord(
        String policyNumber,
        String policyType,
        String policyStatus,
        int effectiveDate,
        int expiryDate,
        String holderId,
        String agentCode,
        String branchCode,
        BigDecimal totalPremium,
        BigDecimal deductible,
        BigDecimal limit,
        int inceptionDate,
        int renewalCount,
        String uwStatus,
        int riskScore,
        String webIndicator,
        String apiFlag,
        int lastUpdated,
        String updatedBy) {

    /** Total bytes including FILLER, as computed by compat/check_layouts.py. */
    public static final int RECORD_LENGTH = 125;
    public static final int POLICY_NUMBER_LENGTH = 12;
    public static final int RISK_SCORE_DIGITS = 3;

    public PolicyRecord {
        policyNumber = alnum("POLICY-NUMBER", policyNumber, POLICY_NUMBER_LENGTH);
        policyType = alnum("POLICY-TYPE", policyType, 3);
        policyStatus = alnum("POLICY-STATUS", policyStatus, 2);
        effectiveDate = date("POLICY-EFFECTIVE-DATE", effectiveDate);
        expiryDate = date("POLICY-EXPIRY-DATE", expiryDate);
        holderId = alnum("POLICY-HOLDER-ID", holderId, 10);
        agentCode = alnum("POLICY-AGENT-CODE", agentCode, 6);
        branchCode = alnum("POLICY-BRANCH-CODE", branchCode, 4);
        totalPremium = packed("POLICY-TOTAL-PREMIUM", totalPremium, 9, 2);
        deductible = packed("POLICY-DEDUCTIBLE", deductible, 7, 2);
        limit = packed("POLICY-LIMIT", limit, 11, 2);
        inceptionDate = date("POLICY-INCEPTION-DATE", inceptionDate);
        renewalCount = unsigned("POLICY-RENEWAL-COUNT", renewalCount, 3);
        uwStatus = alnum("POLICY-UW-STATUS", uwStatus, 2);
        riskScore = unsigned("POLICY-RISK-SCORE", riskScore, RISK_SCORE_DIGITS);
        webIndicator = alnum("POLICY-WEB-IND", webIndicator, 1);
        apiFlag = alnum("POLICY-API-FLAG", apiFlag, 1);
        lastUpdated = date("POLICY-LAST-UPDATED", lastUpdated);
        updatedBy = alnum("POLICY-UPDATED-BY", updatedBy, 8);
    }

    /**
     * The record as UNDWRT holds it after 2000-READ-POLICY-DATA: only the columns the SELECT
     * fetches are populated, everything else keeps its initial SPACES / ZERO value.
     */
    public static PolicyRecord fromUnderwritingSelect(String policyNumber, String policyType,
            BigDecimal totalPremium, BigDecimal limit, String branchCode) {
        return new PolicyRecord(policyNumber, policyType, "", 0, 0, "", "", branchCode,
                totalPremium, null, limit, 0, 0, "", 0, "", "", 0, "");
    }

    /** 5000-RENDER-DECISION: MOVE WS-RISK-SCORE TO POLICY-RISK-SCORE, MOVE WS-DECISION TO POLICY-UW-STATUS. */
    public PolicyRecord withDecision(String decisionCode, int policyRiskScore) {
        return new PolicyRecord(policyNumber, policyType, policyStatus, effectiveDate, expiryDate, holderId,
                agentCode, branchCode, totalPremium, deductible, limit, inceptionDate, renewalCount,
                CopybookFields.oneOf("POLICY-UW-STATUS", decisionCode, 2, "AP", "RS", "RM", "DC"),
                policyRiskScore, webIndicator, apiFlag, lastUpdated, updatedBy);
    }
}
