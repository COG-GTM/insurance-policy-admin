       IDENTIFICATION DIVISION.
       PROGRAM-ID. UNDWRT.
      ******************************************************************
      * UNDWRT - Underwriting Decision Program
      * CICS Transaction: PUWR
      * System:   Policy Administration System (PAS)
      * Author:   R. Patel
      * Date:     1999-01-20
      * Modified: 2004-03-15 - Added terrorism risk scoring
      *           2011-07-01 - Added catastrophe zone check
      *           2018-12-01 - Added cyber risk assessment
      *           2026-10-30 - Operator identity (ASSIGN USERID) on
      *           the decision and policy rows, branch authority via
      *           RACF FACILITY ACME.PAS.UW.DECIDE.<branch>, final or
      *           overridden decisions need ACME.PAS.UW.OVERRIDE
      *
      * Evaluates underwriting risk for new and renewal policies.
      * Applies risk scoring rules, checks accumulation limits,
      * and renders accept/decline/refer decision. Results are
      * written to UNDERWRITING_DECISIONS table and MQ queue.
      *
      * RISK SCORING (hardcoded - see TODO):
      *   0-299  = Auto-Accept
      *   300-599 = Refer to Senior UW
      *   600-799 = Refer to Manager
      *   800+   = Auto-Decline
      *
      * AUTHORIZATION (fails closed):
      *   - Operator must hold RACF UPDATE on FACILITY profile
      *     ACME.PAS.UW.DECIDE.<branch> of the target policy.
      *   - If UW_STATUS is already final (AP/DC) or the latest
      *     decision row carries OVERRIDE_BY, the operator must also
      *     hold UPDATE on ACME.PAS.UW.OVERRIDE; the new row then
      *     records OVERRIDE_BY/OVERRIDE_REASON.
      ******************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'UNDWRT'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-RISK-SCORE             PIC 9(04).
       01  WS-DECISION               PIC X(02).
           88  DEC-ACCEPT             VALUE 'AP'.
           88  DEC-REFER-SR           VALUE 'RS'.
           88  DEC-REFER-MGR          VALUE 'RM'.
           88  DEC-DECLINE            VALUE 'DC'.
       01  WS-DECISION-REASON        PIC X(100).
       01  WS-LOSS-RATIO             PIC S9(03)V99 COMP-3.
       01  WS-CLAIM-COUNT            PIC 9(05).
       01  WS-TOTAL-INCURRED         PIC S9(11)V99 COMP-3.
       01  WS-ACCUM-LIMIT            PIC S9(13)V99 COMP-3.
       01  WS-ZONE-RISK-FACTOR       PIC S9(01)V99 COMP-3.
       01  WS-MQ-QUEUE               PIC X(48)
           VALUE 'ACME.PAS.UNDERWRITING.DECISION'.
       01  WS-OPERATOR-ID            PIC X(08).
       01  WS-INPUT-POLICY-NUMBER    PIC X(12).
       01  WS-PRIOR-UW-STATUS        PIC X(02).
           88  PRIOR-UW-FINAL         VALUE 'AP' 'DC'.
       01  WS-PRIOR-DECISION-CODE    PIC X(02).
       01  WS-PRIOR-OVERRIDE-BY      PIC X(08).
       01  WS-OVERRIDE-SW            PIC X(01).
           88  OVERRIDE-REQUIRED      VALUE 'Y'.
           88  OVERRIDE-NOT-REQUIRED  VALUE 'N'.
       01  WS-OVERRIDE-BY            PIC X(08).
       01  WS-OVERRIDE-REASON        PIC X(200).
       01  WS-SEC-RESCLASS           PIC X(08) VALUE 'FACILITY'.
       01  WS-SEC-UPDATE-CVDA        PIC S9(08) COMP.
       01  WS-UW-DECIDE-RESID.
           05  FILLER                PIC X(19)
               VALUE 'ACME.PAS.UW.DECIDE.'.
           05  WS-UW-DECIDE-BRANCH   PIC X(04).
       01  WS-UW-OVERRIDE-RESID      PIC X(20)
           VALUE 'ACME.PAS.UW.OVERRIDE'.
       01  WS-NOT-AUTH-MSG           PIC X(40)
           VALUE 'NOT AUTHORIZED FOR UNDERWRITING DECISION'.
       01  WS-NULL-INDICATORS.
           05  WS-IND-AGENT-CODE     PIC S9(04) COMP.
           05  WS-IND-BRANCH-CODE    PIC S9(04) COMP.
           05  WS-IND-DEDUCTIBLE     PIC S9(04) COMP.
           05  WS-IND-INCEPTION-DATE PIC S9(04) COMP.
           05  WS-IND-RENEWAL-COUNT  PIC S9(04) COMP.
           05  WS-IND-UW-STATUS      PIC S9(04) COMP.
           05  WS-IND-RISK-SCORE     PIC S9(04) COMP.
           05  WS-IND-WEB-IND        PIC S9(04) COMP.
           05  WS-IND-API-FLAG       PIC S9(04) COMP.
           05  WS-IND-PRIOR-OVR-BY   PIC S9(04) COMP.
           05  WS-IND-OVERRIDE-BY    PIC S9(04) COMP.
           05  WS-IND-OVERRIDE-RSN   PIC S9(04) COMP.

       COPY POLICY-RECORD.
       COPY CUSTOMER-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-READ-POLICY-DATA THRU 2000-EXIT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 2500-AUTHORIZE-BRANCH
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 2600-CHECK-PRIOR-DECISION
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-CALCULATE-RISK-SCORE
               PERFORM 4000-CHECK-ACCUMULATION
               PERFORM 5000-RENDER-DECISION
               PERFORM 6000-WRITE-DECISION
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 7000-SEND-MQ-NOTIFICATION
               PERFORM 8000-DISPLAY-RESULT
           ELSE
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           MOVE SPACES TO WS-DECISION-REASON
           MOVE 0 TO WS-RISK-SCORE
           MOVE SPACES TO WS-OPERATOR-ID
           SET OVERRIDE-NOT-REQUIRED TO TRUE
           EXEC CICS ASKTIME ABSTIME(WS-CURRENT-DATE) END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
               YYYYMMDD(WS-CURRENT-DATE)
           END-EXEC
           EXEC CICS ASSIGN
               USERID(WS-OPERATOR-ID)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
              OR WS-OPERATOR-ID = SPACES
               MOVE 'UNABLE TO IDENTIFY OPERATOR - SIGN ON TO CICS'
                   TO WS-ERROR-MSG
           END-IF
           .

       2000-READ-POLICY-DATA.
           EXEC CICS RECEIVE
               MAP('UWRTMAP')
               MAPSET('UWRTMAPS')
               INTO(POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING UNDERWRITING DATA'
                   TO WS-ERROR-MSG
               GO TO 2000-EXIT
           END-IF
      *    Only the policy number is taken from the terminal; every
      *    other field (and the MQ payload) comes from DB2.
           MOVE POLICY-NUMBER TO WS-INPUT-POLICY-NUMBER
           INITIALIZE POLICY-RECORD
           MOVE WS-INPUT-POLICY-NUMBER TO POLICY-NUMBER
           IF POLICY-NUMBER = SPACES
               MOVE 'POLICY NUMBER IS REQUIRED' TO WS-ERROR-MSG
               GO TO 2000-EXIT
           END-IF
           EXEC SQL
               SELECT P.POLICY_NUMBER, P.POLICY_TYPE, P.POLICY_STATUS,
                      P.EFFECTIVE_DATE, P.EXPIRY_DATE,
                      P.POLICYHOLDER_ID, P.AGENT_CODE, P.BRANCH_CODE,
                      P.TOTAL_PREMIUM, P.DEDUCTIBLE, P.COVERAGE_LIMIT,
                      P.INCEPTION_DATE, P.RENEWAL_COUNT,
                      P.UW_STATUS, P.RISK_SCORE,
                      P.WEB_INDICATOR, P.API_FLAG,
                      C.CUST_TYPE, C.CREDIT_SCORE, C.RISK_TIER
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-HOLDER-ID,
                    :POLICY-AGENT-CODE :WS-IND-AGENT-CODE,
                    :POLICY-BRANCH-CODE :WS-IND-BRANCH-CODE,
                    :POLICY-TOTAL-PREMIUM,
                    :POLICY-DEDUCTIBLE :WS-IND-DEDUCTIBLE,
                    :POLICY-LIMIT,
                    :POLICY-INCEPTION-DATE :WS-IND-INCEPTION-DATE,
                    :POLICY-RENEWAL-COUNT :WS-IND-RENEWAL-COUNT,
                    :POLICY-UW-STATUS :WS-IND-UW-STATUS,
                    :POLICY-RISK-SCORE :WS-IND-RISK-SCORE,
                    :POLICY-WEB-IND :WS-IND-WEB-IND,
                    :POLICY-API-FLAG :WS-IND-API-FLAG,
                    :CUST-TYPE, :CUST-CREDIT-SCORE,
                    :CUST-RISK-TIER
               FROM POLICIES P
               JOIN POLICY_HOLDERS C
                 ON P.POLICYHOLDER_ID = C.CUST_ID
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY/CUSTOMER DATA NOT FOUND'
                   TO WS-ERROR-MSG
               GO TO 2000-EXIT
           END-IF
           IF WS-IND-BRANCH-CODE < 0
               MOVE SPACES TO POLICY-BRANCH-CODE
           END-IF
           IF WS-IND-UW-STATUS < 0
               MOVE SPACES TO POLICY-UW-STATUS
           END-IF
           MOVE POLICY-UW-STATUS TO WS-PRIOR-UW-STATUS
           .
       2000-EXIT.
           EXIT.

       2500-AUTHORIZE-BRANCH.
      *    Operator must hold UPDATE on ACME.PAS.UW.DECIDE.<branch>
           IF POLICY-BRANCH-CODE = SPACES
               MOVE WS-NOT-AUTH-MSG TO WS-ERROR-MSG
           ELSE
               MOVE POLICY-BRANCH-CODE TO WS-UW-DECIDE-BRANCH
               EXEC CICS QUERY SECURITY
                   RESCLASS(WS-SEC-RESCLASS)
                   RESID(WS-UW-DECIDE-RESID)
                   RESIDLENGTH(LENGTH OF WS-UW-DECIDE-RESID)
                   UPDATE(WS-SEC-UPDATE-CVDA)
                   RESP(WS-RESPONSE-CODE)
               END-EXEC
               IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
                  OR WS-SEC-UPDATE-CVDA NOT = DFHVALUE(UPDATABLE)
                   MOVE WS-NOT-AUTH-MSG TO WS-ERROR-MSG
               END-IF
           END-IF
           .

       2600-CHECK-PRIOR-DECISION.
      *    A final status or a recorded human override may only be
      *    superseded by an operator holding ACME.PAS.UW.OVERRIDE.
           MOVE SPACES TO WS-PRIOR-DECISION-CODE
           MOVE SPACES TO WS-PRIOR-OVERRIDE-BY
           EXEC SQL
               SELECT DECISION_CODE, OVERRIDE_BY
               INTO :WS-PRIOR-DECISION-CODE,
                    :WS-PRIOR-OVERRIDE-BY :WS-IND-PRIOR-OVR-BY
               FROM UNDERWRITING_DECISIONS
               WHERE POLICY_NUMBER = :POLICY-NUMBER
               ORDER BY DECISION_DATE DESC, CREATED_TIMESTAMP DESC
               FETCH FIRST 1 ROW ONLY
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   IF WS-IND-PRIOR-OVR-BY < 0
                       MOVE SPACES TO WS-PRIOR-OVERRIDE-BY
                   END-IF
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   MOVE 'ERROR READING PRIOR UNDERWRITING DECISION'
                       TO WS-ERROR-MSG
           END-EVALUATE
           IF WS-ERROR-MSG = SPACES
              AND (PRIOR-UW-FINAL
                   OR WS-PRIOR-OVERRIDE-BY NOT = SPACES)
               SET OVERRIDE-REQUIRED TO TRUE
               EXEC CICS QUERY SECURITY
                   RESCLASS(WS-SEC-RESCLASS)
                   RESID(WS-UW-OVERRIDE-RESID)
                   RESIDLENGTH(LENGTH OF WS-UW-OVERRIDE-RESID)
                   UPDATE(WS-SEC-UPDATE-CVDA)
                   RESP(WS-RESPONSE-CODE)
               END-EXEC
               IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
                  OR WS-SEC-UPDATE-CVDA NOT = DFHVALUE(UPDATABLE)
                   MOVE
                   'UW DECISION LOCKED - OVERRIDE AUTHORITY REQUIRED'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           .

       3000-CALCULATE-RISK-SCORE.
      *    Base score from credit
           IF CUST-CREDIT-SCORE > 0
               COMPUTE WS-RISK-SCORE =
                   (900 - CUST-CREDIT-SCORE) / 2
           ELSE
               MOVE 400 TO WS-RISK-SCORE
           END-IF

      *    Risk tier adjustment
           IF CUST-TIER-SUBSTAND
               ADD 200 TO WS-RISK-SCORE
           END-IF
           IF CUST-TIER-PREFERRED
               SUBTRACT 100 FROM WS-RISK-SCORE
           END-IF

      *    Loss history check
           EXEC SQL
               SELECT COUNT(*), COALESCE(SUM(INCURRED_AMOUNT), 0)
               INTO :WS-CLAIM-COUNT, :WS-TOTAL-INCURRED
               FROM ACMEINS.CLAIMS
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND CLAIM_DATE >= CURRENT DATE - 5 YEARS
           END-EXEC
           IF WS-CLAIM-COUNT > 3
               ADD 150 TO WS-RISK-SCORE
           END-IF
           IF WS-CLAIM-COUNT > 5
               ADD 200 TO WS-RISK-SCORE
           END-IF

      *    Loss ratio check
           IF POLICY-TOTAL-PREMIUM > 0
               COMPUTE WS-LOSS-RATIO =
                   (WS-TOTAL-INCURRED / POLICY-TOTAL-PREMIUM)
                   * 100
               IF WS-LOSS-RATIO > 80
                   ADD 200 TO WS-RISK-SCORE
               END-IF
           END-IF

      *    High limit surcharge
           IF POLICY-LIMIT > 5000000
               ADD 100 TO WS-RISK-SCORE
           END-IF
           IF POLICY-LIMIT > 10000000
               ADD 200 TO WS-RISK-SCORE
           END-IF
           .

       4000-CHECK-ACCUMULATION.
      *    Check geographic accumulation of risk
           EXEC SQL
               SELECT COALESCE(SUM(COVERAGE_LIMIT), 0)
               INTO :WS-ACCUM-LIMIT
               FROM POLICIES
               WHERE BRANCH_CODE = :POLICY-BRANCH-CODE
                 AND POLICY_STATUS = 'AC'
           END-EXEC
      *    TODO: Accumulation limits should be configurable
      *    Hardcoded $500M limit per branch - R.Patel 2011
           IF WS-ACCUM-LIMIT > 500000000
               ADD 300 TO WS-RISK-SCORE
               STRING 'ACCUMULATION LIMIT EXCEEDED FOR BRANCH '
                      POLICY-BRANCH-CODE
                   DELIMITED BY SIZE
                   INTO WS-DECISION-REASON
           END-IF
           .

       5000-RENDER-DECISION.
           EVALUATE TRUE
               WHEN WS-RISK-SCORE < 300
                   SET DEC-ACCEPT TO TRUE
                   MOVE 'AUTO-ACCEPTED: LOW RISK'
                       TO WS-DECISION-REASON
               WHEN WS-RISK-SCORE < 600
                   SET DEC-REFER-SR TO TRUE
                   MOVE 'REFERRED TO SENIOR UNDERWRITER'
                       TO WS-DECISION-REASON
               WHEN WS-RISK-SCORE < 800
                   SET DEC-REFER-MGR TO TRUE
                   MOVE 'REFERRED TO UW MANAGER'
                       TO WS-DECISION-REASON
               WHEN OTHER
                   SET DEC-DECLINE TO TRUE
                   MOVE 'AUTO-DECLINED: HIGH RISK'
                       TO WS-DECISION-REASON
           END-EVALUATE
           MOVE WS-RISK-SCORE TO POLICY-RISK-SCORE
           MOVE WS-DECISION TO POLICY-UW-STATUS
           MOVE WS-CURRENT-DATE TO POLICY-LAST-UPDATED
           MOVE WS-OPERATOR-ID TO POLICY-UPDATED-BY
           IF OVERRIDE-REQUIRED
               MOVE WS-OPERATOR-ID TO WS-OVERRIDE-BY
               MOVE 0 TO WS-IND-OVERRIDE-BY
               MOVE 0 TO WS-IND-OVERRIDE-RSN
               MOVE SPACES TO WS-OVERRIDE-REASON
               STRING 'RE-UNDERWRITTEN OVER PRIOR UW_STATUS '
                      WS-PRIOR-UW-STATUS
                      ' / DECISION '
                      WS-PRIOR-DECISION-CODE
                      ' OVERRIDE_BY '
                      WS-PRIOR-OVERRIDE-BY
                   DELIMITED BY SIZE
                   INTO WS-OVERRIDE-REASON
           ELSE
               MOVE SPACES TO WS-OVERRIDE-BY
               MOVE SPACES TO WS-OVERRIDE-REASON
               MOVE -1 TO WS-IND-OVERRIDE-BY
               MOVE -1 TO WS-IND-OVERRIDE-RSN
           END-IF
           .

       6000-WRITE-DECISION.
      *    UW_STATUS predicate rejects a status changed by someone
      *    else since 2000; both rows commit together or not at all.
           EXEC SQL
               UPDATE POLICIES
               SET UW_STATUS = :WS-DECISION,
                   RISK_SCORE = :WS-RISK-SCORE,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = :WS-OPERATOR-ID
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND COALESCE(UW_STATUS, '  ') = :WS-PRIOR-UW-STATUS
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY CHANGED BY ANOTHER USER - RETRY'
                   TO WS-ERROR-MSG
           ELSE
               EXEC SQL
                   INSERT INTO UNDERWRITING_DECISIONS
                   (POLICY_NUMBER, DECISION_DATE, DECISION_CODE,
                    RISK_SCORE, DECISION_REASON,
                    UNDERWRITER_ID, OVERRIDE_REASON, OVERRIDE_BY,
                    CREATED_TIMESTAMP)
                   VALUES
                   (:POLICY-NUMBER, :WS-CURRENT-DATE,
                    :WS-DECISION, :WS-RISK-SCORE,
                    :WS-DECISION-REASON,
                    :WS-OPERATOR-ID,
                    :WS-OVERRIDE-REASON :WS-IND-OVERRIDE-RSN,
                    :WS-OVERRIDE-BY :WS-IND-OVERRIDE-BY,
                    CURRENT TIMESTAMP)
               END-EXEC
               IF SQLCODE = -803
                   MOVE 'DECISION ALREADY RECORDED TODAY FOR POLICY'
                       TO WS-ERROR-MSG
               ELSE
                   IF SQLCODE NOT = 0
                       MOVE 'ERROR WRITING UNDERWRITING DECISION'
                           TO WS-ERROR-MSG
                   END-IF
               END-IF
           END-IF
           IF WS-ERROR-MSG NOT = SPACES
               EXEC CICS SYNCPOINT ROLLBACK END-EXEC
           END-IF
           .

       7000-SEND-MQ-NOTIFICATION.
           EXEC CICS WRITEQ TD
               QUEUE(WS-MQ-QUEUE)
               FROM(POLICY-RECORD)
               LENGTH(LENGTH OF POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           .

       8000-DISPLAY-RESULT.
           EXEC CICS SEND
               MAP('UWRTRES')
               MAPSET('UWRTMAPS')
               FROM(POLICY-RECORD)
               ERASE
           END-EXEC
           .

       9000-SEND-ERROR.
           EXEC CICS SEND TEXT
               FROM(WS-ERROR-MSG)
               LENGTH(79)
               ERASE
           END-EXEC
           .

       9999-RETURN.
           EXEC CICS RETURN
               TRANSID('PUWR')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
