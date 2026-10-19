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
       01  WS-USER-ID                PIC X(08).
       01  WS-REQ-POLICY-NUMBER      PIC X(12).
       01  WS-AUTH-COUNT             PIC S9(09) COMP-5.
       01  WS-OPEN-CLAIM-COUNT       PIC S9(09) COMP-5.
       01  WS-OPEN-CLAIM-LIMIT       PIC S9(03) COMP-3 VALUE 3.
       01  WS-ROWS-UPDATED           PIC S9(09) COMP-5.
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

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.
       COPY PREMIUM-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-READ-EXISTING-POLICY
           IF WS-ERROR-MSG = SPACES
               PERFORM 2500-CHECK-POLICY-AUTHORITY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-CHECK-RENEWAL-ELIGIBILITY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-NEW-PREMIUM
               PERFORM 5000-APPLY-RATE-CAP
               PERFORM 6000-CREATE-RENEWAL-TERM
               PERFORM 7000-UPDATE-COVERAGES
               IF WS-ERROR-MSG = SPACES
                   PERFORM 8000-SEND-CONFIRMATION
               END-IF
           ELSE
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           MOVE SPACES TO WS-USER-ID
           EXEC CICS ASSIGN
               USERID(WS-USER-ID)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               OR WS-USER-ID = SPACES
               MOVE 'UNABLE TO DETERMINE SIGNED-ON USER' TO WS-ERROR-MSG
           END-IF
           EXEC CICS ASKTIME
               ABSTIME(WS-CURRENT-DATE)
           END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
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
           MOVE POLICY-NUMBER TO WS-REQ-POLICY-NUMBER
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                      TOTAL_PREMIUM, EXPIRY_DATE, RENEWAL_COUNT,
                      UW_STATUS, COALESCE(AGENT_CODE, ' '),
                      COALESCE(BRANCH_CODE, ' ')
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-TOTAL-PREMIUM, :POLICY-EXPIRY-DATE,
                    :POLICY-RENEWAL-COUNT, :POLICY-UW-STATUS,
                    :POLICY-AGENT-CODE, :POLICY-BRANCH-CODE
               FROM ACMEINS.POLICIES
               WHERE POLICY_NUMBER = :WS-REQ-POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
           END-IF
           .
       2000-EXIT.
           EXIT.

       2500-CHECK-POLICY-AUTHORITY.
      *    Object-level authorization: the signed-on user must be
      *    entitled to the agent or branch that owns this policy.
      *    RACF only gates execution of transaction PRWL, not which
      *    policies may be renewed through it.
           MOVE ZERO TO WS-AUTH-COUNT
           IF POLICY-AGENT-CODE = SPACES
               AND POLICY-BRANCH-CODE = SPACES
      *        Unassigned policy - only an unrestricted ID may renew
               EXEC SQL
                   SELECT COUNT(*)
                   INTO :WS-AUTH-COUNT
                   FROM ACMEINS.USER_POLICY_AUTHORITY
                   WHERE USER_ID = :WS-USER-ID
                     AND ALL_POLICIES_IND = 'Y'
               END-EXEC
           ELSE
               EXEC SQL
                   SELECT COUNT(*)
                   INTO :WS-AUTH-COUNT
                   FROM ACMEINS.USER_POLICY_AUTHORITY
                   WHERE USER_ID = :WS-USER-ID
                     AND (ALL_POLICIES_IND = 'Y'
                          OR AGENT_CODE = :POLICY-AGENT-CODE
                          OR BRANCH_CODE = :POLICY-BRANCH-CODE)
               END-EXEC
           END-IF
           IF SQLCODE NOT = 0
               MOVE 'ERROR CHECKING RENEWAL AUTHORITY' TO WS-ERROR-MSG
           ELSE
               IF WS-AUTH-COUNT = ZERO
      *            Same wording as a missing policy so the screen
      *            cannot be used to enumerate other agents' policies
                   MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
               END-IF
           END-IF
           .

       3000-CHECK-RENEWAL-ELIGIBILITY.
           IF NOT POL-STAT-ACTIVE AND NOT POL-STAT-EXPIRED
               MOVE 'POLICY NOT ELIGIBLE FOR RENEWAL' TO WS-ERROR-MSG
           ELSE
      *        Check for outstanding claims
               MOVE ZERO TO WS-OPEN-CLAIM-COUNT
               EXEC SQL
                   SELECT COUNT(*)
                   INTO :WS-OPEN-CLAIM-COUNT
                   FROM ACMEINS.CLAIMS
                   WHERE POLICY_NUMBER = :WS-REQ-POLICY-NUMBER
                     AND CLAIM_STATUS = 'OPEN'
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'ERROR CHECKING OPEN CLAIMS' TO WS-ERROR-MSG
               ELSE
                   IF WS-OPEN-CLAIM-COUNT > WS-OPEN-CLAIM-LIMIT
                       MOVE 'RENEWAL BLOCKED - OPEN CLAIMS EXCEED LIMIT'
                           TO WS-ERROR-MSG
                   END-IF
               END-IF
           END-IF
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
               UPDATE ACMEINS.POLICIES
               SET POLICY_STATUS = :POLICY-STATUS,
                   EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE,
                   TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   RENEWAL_COUNT = :POLICY-RENEWAL-COUNT,
                   UW_STATUS = :POLICY-UW-STATUS,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = 'POLRNW'
               WHERE POLICY_NUMBER = :WS-REQ-POLICY-NUMBER
                 AND COALESCE(AGENT_CODE, ' ') = :POLICY-AGENT-CODE
                 AND COALESCE(BRANCH_CODE, ' ') = :POLICY-BRANCH-CODE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR UPDATING RENEWAL' TO WS-ERROR-MSG
               PERFORM 9000-SEND-ERROR
           ELSE
               MOVE SQLERRD(3) TO WS-ROWS-UPDATED
               IF WS-ROWS-UPDATED = ZERO
      *            Ownership changed between the read and the update
                   MOVE 'RENEWAL NOT APPLIED - POLICY CHANGED'
                       TO WS-ERROR-MSG
                   PERFORM 9000-SEND-ERROR
               END-IF
           END-IF
           .

       7000-UPDATE-COVERAGES.
      *    Extend coverage dates to match new policy term
           IF WS-ERROR-MSG = SPACES
               EXEC SQL
                   UPDATE ACMEINS.COVERAGES AS C
                   SET EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                       EXPIRY_DATE = :POLICY-EXPIRY-DATE
                   WHERE C.POLICY_NUMBER = :WS-REQ-POLICY-NUMBER
                     AND C.STATUS = 'AC'
                     AND EXISTS (SELECT 1
                                 FROM ACMEINS.POLICIES P
                                 WHERE P.POLICY_NUMBER = C.POLICY_NUMBER
                                   AND COALESCE(P.AGENT_CODE, ' ') =
                                       :POLICY-AGENT-CODE
                                   AND COALESCE(P.BRANCH_CODE, ' ') =
                                       :POLICY-BRANCH-CODE)
               END-EXEC
               IF SQLCODE NOT = 0 AND SQLCODE NOT = 100
                   MOVE 'DB2 ERROR UPDATING COVERAGES' TO WS-ERROR-MSG
                   PERFORM 9000-SEND-ERROR
               END-IF
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
