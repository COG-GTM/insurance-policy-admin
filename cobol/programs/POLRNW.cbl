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
      * Policy-level authority: RACF profiles in class PASPOLCY named
      * PAS.AGENT.<agent>, PAS.BRANCH.<branch> or PAS.UNASSIGNED;
      * UPDATE access to one of them is required to renew.
       01  WS-AUTH-CLASS             PIC X(08) VALUE 'PASPOLCY'.
       01  WS-AUTH-RESID             PIC X(44).
       01  WS-AUTH-RESID-LEN         PIC S9(08) COMP.
       01  WS-AUTH-PTR               PIC S9(04) COMP.
       01  WS-AUTH-UPDATE            PIC S9(08) COMP.
       01  WS-AUTH-SW                PIC X(01).
           88  AUTH-GRANTED          VALUE 'Y'.
       01  WS-OPEN-CLAIM-COUNT       PIC S9(09) COMP-5.
       01  WS-OPEN-CLAIM-LIMIT       PIC S9(04) COMP VALUE 3.
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
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-READ-EXISTING-POLICY THRU 2000-EXIT
           END-IF
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
               IF WS-ERROR-MSG = SPACES
                   PERFORM 7000-UPDATE-COVERAGES
               END-IF
               IF WS-ERROR-MSG = SPACES
                   PERFORM 8000-SEND-CONFIRMATION
               ELSE
                   PERFORM 9000-SEND-ERROR
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
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                      TOTAL_PREMIUM, EXPIRY_DATE, RENEWAL_COUNT,
                      UW_STATUS, COALESCE(AGENT_CODE, ' '),
                      COALESCE(BRANCH_CODE, ' ')
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-TOTAL-PREMIUM, :POLICY-EXPIRY-DATE,
                    :POLICY-RENEWAL-COUNT, :POLICY-UW-STATUS,
                    :POLICY-AGENT-CODE, :POLICY-BRANCH-CODE
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
           END-IF
           .
       2000-EXIT.
           EXIT.

       2500-CHECK-POLICY-AUTHORITY.
      *    RACF transaction security only gates who may run PRWL; the
      *    signed-on user must also hold UPDATE on the profile for the
      *    agent or branch that owns this policy.
           MOVE 'N' TO WS-AUTH-SW
           IF POLICY-AGENT-CODE = SPACES
               AND POLICY-BRANCH-CODE = SPACES
               MOVE SPACES TO WS-AUTH-RESID
               MOVE 1 TO WS-AUTH-PTR
               STRING 'PAS.UNASSIGNED' DELIMITED BY SIZE
                   INTO WS-AUTH-RESID WITH POINTER WS-AUTH-PTR
               END-STRING
               PERFORM 2510-QUERY-POLICY-RESOURCE
           ELSE
               IF POLICY-AGENT-CODE NOT = SPACES
                   MOVE SPACES TO WS-AUTH-RESID
                   MOVE 1 TO WS-AUTH-PTR
                   STRING 'PAS.AGENT.' DELIMITED BY SIZE
                          POLICY-AGENT-CODE DELIMITED BY SPACE
                       INTO WS-AUTH-RESID WITH POINTER WS-AUTH-PTR
                   END-STRING
                   PERFORM 2510-QUERY-POLICY-RESOURCE
               END-IF
               IF NOT AUTH-GRANTED
                   AND POLICY-BRANCH-CODE NOT = SPACES
                   MOVE SPACES TO WS-AUTH-RESID
                   MOVE 1 TO WS-AUTH-PTR
                   STRING 'PAS.BRANCH.' DELIMITED BY SIZE
                          POLICY-BRANCH-CODE DELIMITED BY SPACE
                       INTO WS-AUTH-RESID WITH POINTER WS-AUTH-PTR
                   END-STRING
                   PERFORM 2510-QUERY-POLICY-RESOURCE
               END-IF
           END-IF
           IF NOT AUTH-GRANTED
      *        Same text as a missing policy so PRWL cannot be used
      *        to probe for policies outside the user's book
               MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
           END-IF
           .

       2510-QUERY-POLICY-RESOURCE.
           COMPUTE WS-AUTH-RESID-LEN = WS-AUTH-PTR - 1
           EXEC CICS QUERY SECURITY
               RESCLASS(WS-AUTH-CLASS)
               RESID(WS-AUTH-RESID)
               RESIDLENGTH(WS-AUTH-RESID-LEN)
               UPDATE(WS-AUTH-UPDATE)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE = DFHRESP(NORMAL)
               AND WS-AUTH-UPDATE = DFHVALUE(UPDATABLE)
               MOVE 'Y' TO WS-AUTH-SW
           END-IF
           .

       3000-CHECK-RENEWAL-ELIGIBILITY.
           IF NOT POL-STAT-ACTIVE AND NOT POL-STAT-EXPIRED
               MOVE 'POLICY NOT ELIGIBLE FOR RENEWAL' TO WS-ERROR-MSG
           ELSE
      *        Block renewal while more than 3 claims are open
               MOVE ZERO TO WS-OPEN-CLAIM-COUNT
               EXEC SQL
                   SELECT COUNT(*)
                   INTO :WS-OPEN-CLAIM-COUNT
                   FROM ACMEINS.CLAIMS
                   WHERE POLICY_NUMBER = :POLICY-NUMBER
                     AND CLAIM_STATUS = 'OPEN'
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'ERROR CHECKING OPEN CLAIMS' TO WS-ERROR-MSG
               ELSE
                   IF WS-OPEN-CLAIM-COUNT > WS-OPEN-CLAIM-LIMIT
                       MOVE 'RENEWAL BLOCKED - TOO MANY OPEN CLAIMS'
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
               UPDATE POLICIES
               SET POLICY_STATUS = :POLICY-STATUS,
                   EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE,
                   TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   RENEWAL_COUNT = :POLICY-RENEWAL-COUNT,
                   UW_STATUS = :POLICY-UW-STATUS,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = 'POLRNW'
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND COALESCE(AGENT_CODE, ' ') = :POLICY-AGENT-CODE
                 AND COALESCE(BRANCH_CODE, ' ') = :POLICY-BRANCH-CODE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR UPDATING RENEWAL' TO WS-ERROR-MSG
           ELSE
               IF SQLERRD(3) = ZERO
      *            Ownership changed after the authority check
                   MOVE 'RENEWAL NOT APPLIED - POLICY CHANGED'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
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
