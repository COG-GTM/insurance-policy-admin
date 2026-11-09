package com.acme.insurance.pas.underwriting.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JDBC access to the existing DB2 tables, one method per EXEC SQL in UNDWRT.cbl.
 * Table names are unqualified and resolve against the connection's current schema (ACMEINS),
 * as the static SQL in the COBOL plan does via its QUALIFIER. CHAR key parameters are passed
 * blank-padded to their column length, as the COBOL PIC X host variables are.
 */
@Transactional(noRollbackFor = DataAccessException.class)
public interface UnderwritingRepository extends Repository<UnderwritingDecisionRow, String> {

    /** 2000-READ-POLICY-DATA: SELECT ... FROM POLICIES P JOIN POLICY_HOLDERS C. */
    @Query(value = """
            SELECT P.POLICY_NUMBER, P.POLICY_TYPE,
                   P.TOTAL_PREMIUM, P.COVERAGE_LIMIT,
                   P.BRANCH_CODE,
                   C.CUST_TYPE, C.CREDIT_SCORE, C.RISK_TIER
              FROM POLICIES P
              JOIN POLICY_HOLDERS C
                ON P.POLICYHOLDER_ID = C.CUST_ID
             WHERE P.POLICY_NUMBER = :policyNumber
            """, rowMapperClass = PolicyCustomerRow.Mapper.class)
    List<PolicyCustomerRow> selectPolicyAndCustomer(@Param("policyNumber") String policyNumber);

    /** 3000-CALCULATE-RISK-SCORE: loss history over the last 5 years from ACMEINS.CLAIMS. */
    @Query(value = """
            SELECT COUNT(*) AS CLAIM_COUNT, COALESCE(SUM(INCURRED_AMOUNT), 0) AS TOTAL_INCURRED
              FROM CLAIMS
             WHERE POLICY_NUMBER = :policyNumber
               AND CLAIM_DATE >= :since
            """, rowMapperClass = ClaimsHistory.Mapper.class)
    ClaimsHistory selectClaimsHistory(@Param("policyNumber") String policyNumber, @Param("since") LocalDate since);

    /** 4000-CHECK-ACCUMULATION: SUM(COVERAGE_LIMIT) of active policies in the branch. */
    @Query("""
            SELECT COALESCE(SUM(COVERAGE_LIMIT), 0)
              FROM POLICIES
             WHERE BRANCH_CODE = :branchCode
               AND POLICY_STATUS = :policyStatus
            """)
    BigDecimal selectBranchAccumulation(@Param("branchCode") String branchCode,
            @Param("policyStatus") String policyStatus);

    /** 6000-WRITE-DECISION: INSERT INTO UNDERWRITING_DECISIONS. */
    @Modifying
    @Query("""
            INSERT INTO UNDERWRITING_DECISIONS
                   (POLICY_NUMBER, DECISION_DATE, DECISION_CODE,
                    RISK_SCORE, DECISION_REASON,
                    UNDERWRITER_ID, CREATED_TIMESTAMP)
            VALUES (:policyNumber, :decisionDate,
                    :decisionCode, :riskScore,
                    :decisionReason,
                    :underwriterId, CURRENT_TIMESTAMP)
            """)
    int insertDecision(@Param("policyNumber") String policyNumber,
            @Param("decisionDate") LocalDate decisionDate,
            @Param("decisionCode") String decisionCode,
            @Param("riskScore") int riskScore,
            @Param("decisionReason") String decisionReason,
            @Param("underwriterId") String underwriterId);

    /** 6000-WRITE-DECISION: UPDATE POLICIES SET UW_STATUS, RISK_SCORE, LAST_UPDATED, UPDATED_BY. */
    @Modifying
    @Query("""
            UPDATE POLICIES
               SET UW_STATUS = :decisionCode,
                   RISK_SCORE = :riskScore,
                   LAST_UPDATED = CURRENT_TIMESTAMP,
                   UPDATED_BY = :updatedBy
             WHERE POLICY_NUMBER = :policyNumber
            """)
    int updatePolicyDecision(@Param("policyNumber") String policyNumber,
            @Param("decisionCode") String decisionCode,
            @Param("riskScore") int riskScore,
            @Param("updatedBy") String updatedBy);
}
