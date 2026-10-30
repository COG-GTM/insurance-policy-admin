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
      *           2026-10-30 - Operator identity, policy scope check,
      *                        durable DB2 audit (POLICY_AUDIT_LOG)
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
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-NEW-EXPIRY-DATE        PIC 9(08).
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-RATE-INCREASE-CAP      PIC S9(03)V99 COMP-3
           VALUE 15.00.
       01  WS-RATE-CHANGE-PCT        PIC S9(03)V99 COMP-3.
       01  WS-NEW-PREMIUM            PIC S9(09)V99 COMP-3.
       01  WS-OLD-PREMIUM            PIC S9(09)V99 COMP-3.
       01  WS-PREMIUM-CHANGE         PIC S9(09)V99 COMP-3.
       01  WS-AUDIT-DETAIL           PIC X(100).
       01  WS-USER-ID                PIC X(08).
       01  WS-TERMINAL-ID            PIC X(04).
       01  WS-TRANSACTION-ID         PIC X(04).
       01  WS-NOT-IN-SCOPE-MSG       PIC X(79) VALUE
           'POLICY NOT FOUND OR NOT IN YOUR BOOK OF BUSINESS'.
      * COVID grace period flag - added 2020
       01  WS-COVID-GRACE            PIC X(01) VALUE 'N'.
           88  COVID-GRACE-YES       VALUE 'Y'.
           88  COVID-GRACE-NO        VALUE 'N'.

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.
       COPY PREMIUM-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-READ-EXISTING-POLICY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-CHECK-RENEWAL-ELIGIBILITY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-NEW-PREMIUM
               PERFORM 5000-APPLY-RATE-CAP
               PERFORM 6000-CREATE-RENEWAL-TERM
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 7000-UPDATE-COVERAGES
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 7500-WRITE-AUDIT-TRAIL
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 8000-SEND-CONFIRMATION
           ELSE
               PERFORM 8500-ROLLBACK
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           EXEC CICS ASKTIME
               ABSTIME(WS-CURRENT-DATE)
           END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
               YYYYMMDD(WS-CURRENT-DATE)
           END-EXEC
      *    Identify the signed-on operator; every change is attributed
      *    to this RACF user id and scoped to their USER_ENTITLEMENTS.
           MOVE EIBTRMID TO WS-TERMINAL-ID
           MOVE EIBTRNID TO WS-TRANSACTION-ID
           EXEC CICS ASSIGN
               USERID(WS-USER-ID)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               OR WS-USER-ID = SPACES
               MOVE 'UNABLE TO IDENTIFY SIGNED-ON USER'
                   TO WS-ERROR-MSG
           END-IF
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
           ELSE
               PERFORM 2100-READ-POLICY-IN-SCOPE
           END-IF
           .

       2100-READ-POLICY-IN-SCOPE.
      *    Only policies in the operator's book of business
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                      TOTAL_PREMIUM, EXPIRY_DATE, RENEWAL_COUNT,
                      UW_STATUS
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-TOTAL-PREMIUM, :POLICY-EXPIRY-DATE,
                    :POLICY-RENEWAL-COUNT, :POLICY-UW-STATUS
               FROM POLICIES P
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
                 AND EXISTS
                     (SELECT 1 FROM USER_ENTITLEMENTS E
                       WHERE E.USER_ID = :WS-USER-ID
                         AND E.ACTIVE_IND = 'Y'
                         AND (E.SCOPE_LEVEL = 'A'
                          OR (E.SCOPE_LEVEL = 'B'
                              AND E.BRANCH_CODE = P.BRANCH_CODE)
                          OR (E.SCOPE_LEVEL = 'G'
                              AND E.AGENT_CODE = P.AGENT_CODE)))
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   CONTINUE
               WHEN SQLCODE = 100
                   MOVE WS-NOT-IN-SCOPE-MSG TO WS-ERROR-MSG
               WHEN OTHER
                   MOVE 'DB2 ERROR READING POLICY FOR RENEWAL'
                       TO WS-ERROR-MSG
           END-EVALUATE
           .

       3000-CHECK-RENEWAL-ELIGIBILITY.
           IF NOT POL-STAT-ACTIVE AND NOT POL-STAT-EXPIRED
               MOVE 'POLICY NOT ELIGIBLE FOR RENEWAL' TO WS-ERROR-MSG
           END-IF
      *    Check for outstanding claims
           EXEC SQL
               SELECT COUNT(*)
               INTO :WS-SQLCODE
               FROM ACMEINS.CLAIMS
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND CLAIM_STATUS = 'OPEN'
           END-EXEC
      *    TODO: Business wants to block renewal if >3 open claims
      *    but this was never fully implemented - JH 2005
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
           MOVE 'AC' TO POLICY-STATUS
           MOVE 'PN' TO POLICY-UW-STATUS

           EXEC SQL
               UPDATE POLICIES P
               SET POLICY_STATUS = :POLICY-STATUS,
                   EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE,
                   TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   RENEWAL_COUNT = :POLICY-RENEWAL-COUNT,
                   UW_STATUS = :POLICY-UW-STATUS,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = :WS-USER-ID
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
                 AND EXISTS
                     (SELECT 1 FROM USER_ENTITLEMENTS E
                       WHERE E.USER_ID = :WS-USER-ID
                         AND E.ACTIVE_IND = 'Y'
                         AND (E.SCOPE_LEVEL = 'A'
                          OR (E.SCOPE_LEVEL = 'B'
                              AND E.BRANCH_CODE = P.BRANCH_CODE)
                          OR (E.SCOPE_LEVEL = 'G'
                              AND E.AGENT_CODE = P.AGENT_CODE)))
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   CONTINUE
               WHEN SQLCODE = 100
                   MOVE WS-NOT-IN-SCOPE-MSG TO WS-ERROR-MSG
               WHEN OTHER
                   MOVE 'DB2 ERROR UPDATING RENEWAL' TO WS-ERROR-MSG
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
           IF SQLCODE < 0
               MOVE 'DB2 ERROR UPDATING COVERAGES' TO WS-ERROR-MSG
           END-IF
           .

       7500-WRITE-AUDIT-TRAIL.
           COMPUTE WS-PREMIUM-CHANGE =
               POLICY-TOTAL-PREMIUM - WS-OLD-PREMIUM
           MOVE SPACES TO WS-AUDIT-DETAIL
           STRING 'RENEWED TERM ' POLICY-EFFECTIVE-DATE
                  ' TO ' POLICY-EXPIRY-DATE
               DELIMITED BY SIZE
               INTO WS-AUDIT-DETAIL
           EXEC SQL
               INSERT INTO POLICY_AUDIT_LOG
               (POLICY_NUMBER, ACTION_CODE, TRANSACTION_ID,
                PROGRAM_ID, USER_ID, TERMINAL_ID,
                CHANGE_SEQ, OLD_PREMIUM, NEW_PREMIUM,
                PREMIUM_ADJUSTMENT, DETAIL, CREATED_TIMESTAMP)
               VALUES
               (:POLICY-NUMBER, 'RNW', :WS-TRANSACTION-ID,
                :WS-PROGRAM-ID, :WS-USER-ID, :WS-TERMINAL-ID,
                :POLICY-RENEWAL-COUNT, :WS-OLD-PREMIUM,
                :POLICY-TOTAL-PREMIUM, :WS-PREMIUM-CHANGE,
                :WS-AUDIT-DETAIL, CURRENT TIMESTAMP)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR WRITING AUDIT RECORD - NOT APPLIED'
                   TO WS-ERROR-MSG
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

       8500-ROLLBACK.
      *    Back out any partial change so nothing unaudited commits
           EXEC CICS SYNCPOINT ROLLBACK
               RESP(WS-RESPONSE-CODE)
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
