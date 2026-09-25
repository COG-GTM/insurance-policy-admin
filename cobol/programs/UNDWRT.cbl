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
      *           2026-09-25 - Record CICS operator (EIBUSERID) on
      *                        decisions; reject unauthenticated users
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
      ******************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'UNDWRT'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-OPERATOR-ID            PIC X(08).
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

       COPY POLICY-RECORD.
       COPY CUSTOMER-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-READ-POLICY-DATA
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-CALCULATE-RISK-SCORE
               PERFORM 4000-CHECK-ACCUMULATION
               PERFORM 5000-RENDER-DECISION
               PERFORM 6000-WRITE-DECISION
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
           MOVE EIBUSERID TO WS-OPERATOR-ID
           IF WS-OPERATOR-ID = SPACES OR LOW-VALUES
               MOVE 'NO AUTHENTICATED OPERATOR - DECISION REJECTED'
                   TO WS-ERROR-MSG
           END-IF
           EXEC CICS ASKTIME ABSTIME(WS-CURRENT-DATE) END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
               YYYYMMDD(WS-CURRENT-DATE)
           END-EXEC
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
           EXEC SQL
               SELECT P.POLICY_NUMBER, P.POLICY_TYPE,
                      P.TOTAL_PREMIUM, P.COVERAGE_LIMIT,
                      P.BRANCH_CODE,
                      C.CUST_TYPE, C.CREDIT_SCORE, C.RISK_TIER
               INTO :POLICY-NUMBER, :POLICY-TYPE,
                    :POLICY-TOTAL-PREMIUM, :POLICY-LIMIT,
                    :POLICY-BRANCH-CODE,
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
           END-IF
           .
       2000-EXIT.
           EXIT.

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
           .

       6000-WRITE-DECISION.
           EXEC SQL
               INSERT INTO UNDERWRITING_DECISIONS
               (POLICY_NUMBER, DECISION_DATE, DECISION_CODE,
                RISK_SCORE, DECISION_REASON,
                UNDERWRITER_ID, CREATED_TIMESTAMP)
               VALUES
               (:POLICY-NUMBER, :WS-CURRENT-DATE,
                :WS-DECISION, :WS-RISK-SCORE,
                :WS-DECISION-REASON,
                :WS-OPERATOR-ID, CURRENT TIMESTAMP)
           END-EXEC

           EXEC SQL
               UPDATE POLICIES
               SET UW_STATUS = :WS-DECISION,
                   RISK_SCORE = :WS-RISK-SCORE,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = :WS-OPERATOR-ID
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
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
