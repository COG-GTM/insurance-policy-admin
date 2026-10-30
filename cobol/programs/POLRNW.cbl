       IDENTIFICATION DIVISION.
       PROGRAM-ID. POLRNW.
      ******************************************************************
      * POLRNW - Policy Renewal Program
      * CICS Transaction: PRWL
      * System:   Policy Administration System (PAS)
      * Author:   J. Henderson
      * Date:     1998-06-15
      * Modified: 2005-03-01 - Added auto-renewal logic
      *           2012-11-15 - Added rate increase cap (regulatory)
      *           2020-02-01 - COVID grace period extension
      *           2026-10-30 - Renewal eligibility: underwriting
      *                        approval, open-claims limit and
      *                        renewal window enforced
      *
      * Processes policy renewals. Reads expiring policy, validates
      * renewal eligibility, applies rate changes, creates new term.
      * Batch mode supported via PREMBAT for mass renewals.
      ******************************************************************
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'POLRNW'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-SQLCODE                PIC S9(09) COMP-5.
       01  WS-ABSTIME                PIC S9(15) COMP-3.
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-NEW-EXPIRY-DATE        PIC 9(08).
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-RATE-INCREASE-CAP      PIC S9(03)V99 COMP-3
           VALUE 15.00.
       01  WS-RATE-CHANGE-PCT        PIC S9(03)V99 COMP-3.
       01  WS-NEW-PREMIUM            PIC S9(09)V99 COMP-3.
       01  WS-OLD-PREMIUM            PIC S9(09)V99 COMP-3.
      * COVID grace period flag - added 2020
       01  WS-COVID-GRACE            PIC X(01) VALUE 'N'.
           88  COVID-GRACE-YES       VALUE 'Y'.
           88  COVID-GRACE-NO        VALUE 'N'.
      * Renewal eligibility rules
       01  WS-MAX-OPEN-CLAIMS        PIC S9(09) COMP-5 VALUE 3.
       01  WS-RENEWAL-WINDOW-DAYS    PIC S9(09) COMP-5 VALUE 60.
       01  WS-RENEWAL-GRACE-DAYS     PIC S9(09) COMP-5 VALUE 30.
       01  WS-OPEN-CLAIMS            PIC S9(09) COMP-5.
       01  WS-TODAY-DAYS             PIC S9(09) COMP-5.
       01  WS-EXPIRY-DAYS            PIC S9(09) COMP-5.
       01  WS-LAST-UW-DECISION       PIC X(02).
           88  LAST-UW-APPROVED      VALUE 'AP'.
       01  WS-LAST-UW-IN-WINDOW      PIC X(01).
           88  LAST-UW-CURRENT       VALUE 'Y'.
      * Values read in 2000; the renewal UPDATE only applies if the
      * row still holds them (blocks double submission of PRWL)
       01  WS-PRIOR-STATUS           PIC X(02).
       01  WS-PRIOR-EXPIRY-DATE      PIC 9(08).
       01  WS-PRIOR-RENEWAL-COUNT    PIC 9(03).

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.
       COPY PREMIUM-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-READ-EXISTING-POLICY THRU 2000-EXIT
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-CHECK-RENEWAL-ELIGIBILITY
                   THRU 3000-EXIT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-NEW-PREMIUM
               PERFORM 5000-APPLY-RATE-CAP
               PERFORM 6000-CREATE-RENEWAL-TERM
               IF WS-ERROR-MSG = SPACES
                   PERFORM 7000-UPDATE-COVERAGES
               END-IF
               IF WS-ERROR-MSG = SPACES
                   PERFORM 8000-SEND-CONFIRMATION
               ELSE
                   EXEC CICS SYNCPOINT ROLLBACK END-EXEC
                   PERFORM 9000-SEND-ERROR
               END-IF
           ELSE
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           EXEC CICS ASKTIME
               ABSTIME(WS-ABSTIME)
           END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-ABSTIME)
               YYYYMMDD(WS-CURRENT-DATE)
           END-EXEC
           .

       2000-READ-EXISTING-POLICY.
           EXEC CICS RECEIVE
               MAP('POLRMAP')
               MAPSET('POLRMAPS')
               INTO(POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING RENEWAL DATA' TO WS-ERROR-MSG
               GO TO 2000-EXIT
           END-IF
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                      TOTAL_PREMIUM, EXPIRY_DATE, RENEWAL_COUNT,
                      UW_STATUS
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-TOTAL-PREMIUM, :POLICY-EXPIRY-DATE,
                    :POLICY-RENEWAL-COUNT, :POLICY-UW-STATUS
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
           ELSE
               MOVE POLICY-STATUS        TO WS-PRIOR-STATUS
               MOVE POLICY-EXPIRY-DATE   TO WS-PRIOR-EXPIRY-DATE
               MOVE POLICY-RENEWAL-COUNT TO WS-PRIOR-RENEWAL-COUNT
           END-IF
           .
       2000-EXIT.
           EXIT.

       3000-CHECK-RENEWAL-ELIGIBILITY.
           IF NOT POL-STAT-ACTIVE AND NOT POL-STAT-EXPIRED
               MOVE 'POLICY NOT ELIGIBLE FOR RENEWAL' TO WS-ERROR-MSG
               GO TO 3000-EXIT
           END-IF
      *    The current term must carry an underwriting approval
           EVALUATE TRUE
               WHEN UW-APPROVED
                   CONTINUE
               WHEN UW-DECLINED
                   MOVE 'RENEWAL BLOCKED - UNDERWRITING DECLINED'
                       TO WS-ERROR-MSG
               WHEN UW-REFERRED
                   MOVE 'RENEWAL BLOCKED - UNDERWRITING REFERRED'
                       TO WS-ERROR-MSG
               WHEN OTHER
                   MOVE 'RENEWAL BLOCKED - UNDERWRITING NOT APPROVED'
                       TO WS-ERROR-MSG
           END-EVALUATE
           IF WS-ERROR-MSG NOT = SPACES
               GO TO 3000-EXIT
           END-IF
           PERFORM 3100-CHECK-RENEWAL-WINDOW
           IF WS-ERROR-MSG NOT = SPACES
               GO TO 3000-EXIT
           END-IF
           PERFORM 3200-CHECK-UW-DECISION
           IF WS-ERROR-MSG NOT = SPACES
               GO TO 3000-EXIT
           END-IF
      *    Business rule: no renewal with more than 3 open claims
           EXEC SQL
               SELECT COUNT(*)
               INTO :WS-OPEN-CLAIMS
               FROM ACMEINS.CLAIMS
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND CLAIM_STATUS = 'OPEN'
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR CHECKING OPEN CLAIMS' TO WS-ERROR-MSG
           ELSE
               IF WS-OPEN-CLAIMS > WS-MAX-OPEN-CLAIMS
                   MOVE 'RENEWAL BLOCKED - TOO MANY OPEN CLAIMS'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           .
       3000-EXIT.
           EXIT.

      *    Renewal is only allowed from WS-RENEWAL-WINDOW-DAYS before
      *    expiry to WS-RENEWAL-GRACE-DAYS after it. A just-renewed
      *    term is a year out, so a repeat PRWL is rejected here.
       3100-CHECK-RENEWAL-WINDOW.
           IF FUNCTION TEST-DATE-YYYYMMDD(WS-CURRENT-DATE) NOT = 0
           OR FUNCTION TEST-DATE-YYYYMMDD(POLICY-EXPIRY-DATE)
                  NOT = 0
               MOVE 'RENEWAL BLOCKED - INVALID TERM DATES'
                   TO WS-ERROR-MSG
           ELSE
               COMPUTE WS-TODAY-DAYS =
                   FUNCTION INTEGER-OF-DATE(WS-CURRENT-DATE)
               COMPUTE WS-EXPIRY-DAYS =
                   FUNCTION INTEGER-OF-DATE(POLICY-EXPIRY-DATE)
               EVALUATE TRUE
                   WHEN WS-TODAY-DAYS <
                        WS-EXPIRY-DAYS - WS-RENEWAL-WINDOW-DAYS
                       MOVE 'RENEWAL BLOCKED - OUTSIDE RENEWAL WINDOW'
                           TO WS-ERROR-MSG
                   WHEN WS-TODAY-DAYS >
                        WS-EXPIRY-DAYS + WS-RENEWAL-GRACE-DAYS
                       MOVE 'RENEWAL BLOCKED - GRACE PERIOD ENDED'
                           TO WS-ERROR-MSG
               END-EVALUATE
           END-IF
           .

      *    UNDWRT must have approved the policy for this renewal: the
      *    latest decision must be 'AP' and dated inside the window.
       3200-CHECK-UW-DECISION.
           MOVE SPACES TO WS-LAST-UW-DECISION
           MOVE 'N' TO WS-LAST-UW-IN-WINDOW
           EXEC SQL
               SELECT D.DECISION_CODE,
                      CASE WHEN D.DECISION_DATE >=
                                P.EXPIRY_DATE
                                - :WS-RENEWAL-WINDOW-DAYS DAYS
                           THEN 'Y' ELSE 'N' END
               INTO :WS-LAST-UW-DECISION, :WS-LAST-UW-IN-WINDOW
               FROM ACMEINS.UNDERWRITING_DECISIONS D
               JOIN ACMEINS.POLICIES P
                 ON P.POLICY_NUMBER = D.POLICY_NUMBER
               WHERE D.POLICY_NUMBER = :POLICY-NUMBER
               ORDER BY D.DECISION_DATE DESC,
                        D.CREATED_TIMESTAMP DESC
               FETCH FIRST 1 ROW ONLY
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 100
                   MOVE 'RENEWAL BLOCKED - NO UNDERWRITING DECISION'
                       TO WS-ERROR-MSG
               WHEN SQLCODE NOT = 0
                   MOVE 'DB2 ERROR CHECKING UNDERWRITING DECISION'
                       TO WS-ERROR-MSG
               WHEN NOT LAST-UW-APPROVED
                   MOVE 'RENEWAL BLOCKED - UNDERWRITING NOT APPROVED'
                       TO WS-ERROR-MSG
               WHEN NOT LAST-UW-CURRENT
                   MOVE 'RENEWAL BLOCKED - UNDERWRITING REVIEW REQUIRED'
                       TO WS-ERROR-MSG
           END-EVALUATE
           .

       4000-CALCULATE-NEW-PREMIUM.
           MOVE POLICY-TOTAL-PREMIUM TO WS-OLD-PREMIUM
      *    Apply standard rate increase (hardcoded - should be
      *    table-driven but actuarial never provided the table)
           COMPUTE WS-NEW-PREMIUM =
               WS-OLD-PREMIUM * 1.05
           COMPUTE WS-RATE-CHANGE-PCT =
               ((WS-NEW-PREMIUM - WS-OLD-PREMIUM)
                / WS-OLD-PREMIUM) * 100
           .

       5000-APPLY-RATE-CAP.
      *    Regulatory rate increase cap - varies by state
      *    Using flat 15% cap for now (TODO: state-specific caps)
           IF WS-RATE-CHANGE-PCT > WS-RATE-INCREASE-CAP
               COMPUTE WS-NEW-PREMIUM =
                   WS-OLD-PREMIUM * (1 + WS-RATE-INCREASE-CAP / 100)
           END-IF
           MOVE WS-NEW-PREMIUM TO POLICY-TOTAL-PREMIUM
           .

       6000-CREATE-RENEWAL-TERM.
           ADD 1 TO POLICY-RENEWAL-COUNT
           MOVE POLICY-EXPIRY-DATE TO POLICY-EFFECTIVE-DATE
      *    Add 1 year to effective date for new expiry
      *    Simple arithmetic - does not handle leap years properly
           ADD 10000 TO POLICY-EXPIRY-DATE
                        GIVING WS-NEW-EXPIRY-DATE
           MOVE WS-NEW-EXPIRY-DATE TO POLICY-EXPIRY-DATE
      *    Only reached with an approved, in-window UW decision, so
      *    the renewed term is activated with UW_STATUS left at 'AP'.
      *    The WHERE clause re-applies the eligibility rules so a
      *    concurrent change or second submission updates no row.
           MOVE 'AC' TO POLICY-STATUS

           EXEC SQL
               UPDATE POLICIES
               SET POLICY_STATUS = :POLICY-STATUS,
                   EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE,
                   TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   RENEWAL_COUNT = :POLICY-RENEWAL-COUNT,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = 'POLRNW'
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND POLICY_STATUS = :WS-PRIOR-STATUS
                 AND EXPIRY_DATE = :WS-PRIOR-EXPIRY-DATE
                 AND RENEWAL_COUNT = :WS-PRIOR-RENEWAL-COUNT
                 AND UW_STATUS = 'AP'
                 AND (SELECT COUNT(*)
                        FROM ACMEINS.CLAIMS C
                       WHERE C.POLICY_NUMBER = :POLICY-NUMBER
                         AND C.CLAIM_STATUS = 'OPEN')
                     <= :WS-MAX-OPEN-CLAIMS
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE NOT = 0
                   MOVE 'DB2 ERROR UPDATING RENEWAL' TO WS-ERROR-MSG
               WHEN SQLERRD(3) NOT = 1
                   MOVE 'RENEWAL NOT APPLIED - POLICY CHANGED'
                       TO WS-ERROR-MSG
           END-EVALUATE
           .

       7000-UPDATE-COVERAGES.
      *    Extend coverage dates to match new policy term
           EXEC SQL
               UPDATE COVERAGES
               SET EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND STATUS = 'AC'
           END-EXEC
           IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
               MOVE 'DB2 ERROR UPDATING COVERAGES' TO WS-ERROR-MSG
           END-IF
           .

       8000-SEND-CONFIRMATION.
           EXEC CICS SEND
               MAP('POLRCONF')
               MAPSET('POLRMAPS')
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
               TRANSID('PRWL')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
