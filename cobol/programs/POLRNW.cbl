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
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-NEW-EXPIRY-DATE        PIC 9(08).
       01  WS-NEW-EXPIRY-PARTS REDEFINES WS-NEW-EXPIRY-DATE.
           05  WS-NEW-EXPIRY-YYYY    PIC 9(04).
           05  WS-NEW-EXPIRY-MMDD    PIC 9(04).
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
               PERFORM 3000-CHECK-RENEWAL-ELIGIBILITY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-NEW-PREMIUM
               PERFORM 5000-APPLY-RATE-CAP
               PERFORM 6000-CREATE-RENEWAL-TERM THRU 6000-EXIT
               IF WS-ERROR-MSG = SPACES
                   PERFORM 7000-UPDATE-COVERAGES
               END-IF
      *        POLICIES and COVERAGES are one unit of work: if either
      *        update failed, back both out before telling the user
               IF WS-ERROR-MSG = SPACES
                   PERFORM 8000-SEND-CONFIRMATION
               ELSE
                   PERFORM 8500-ROLLBACK-RENEWAL
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
                      UW_STATUS
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-TOTAL-PREMIUM, :POLICY-EXPIRY-DATE,
                    :POLICY-RENEWAL-COUNT, :POLICY-UW-STATUS
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND FOR RENEWAL' TO WS-ERROR-MSG
           END-IF
           .
       2000-EXIT.
           EXIT.

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
           IF FUNCTION TEST-DATE-YYYYMMDD(POLICY-EXPIRY-DATE) NOT = 0
               MOVE 'INVALID EXPIRY DATE ON POLICY' TO WS-ERROR-MSG
               GO TO 6000-EXIT
           END-IF
      *    New term runs one year from the old expiry. A 29 February
      *    anniversary falls back to 28 February in a non-leap year.
           MOVE POLICY-EXPIRY-DATE TO WS-NEW-EXPIRY-DATE
           ADD 1 TO WS-NEW-EXPIRY-YYYY
           IF FUNCTION TEST-DATE-YYYYMMDD(WS-NEW-EXPIRY-DATE) NOT = 0
               COMPUTE WS-NEW-EXPIRY-DATE = FUNCTION DATE-OF-INTEGER(
                   FUNCTION INTEGER-OF-DATE(
                       WS-NEW-EXPIRY-YYYY * 10000 + 301) - 1)
           END-IF
           ADD 1 TO POLICY-RENEWAL-COUNT
           MOVE POLICY-EXPIRY-DATE TO POLICY-EFFECTIVE-DATE
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
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR UPDATING RENEWAL' TO WS-ERROR-MSG
           ELSE
               IF SQLERRD(3) NOT = 1
                   MOVE 'RENEWAL NOT APPLIED - POLICY NOT UPDATED'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           .
       6000-EXIT.
           EXIT.

       7000-UPDATE-COVERAGES.
      *    Extend coverage dates to match new policy term
           EXEC SQL
               UPDATE COVERAGES
               SET EFFECTIVE_DATE = :POLICY-EFFECTIVE-DATE,
                   EXPIRY_DATE = :POLICY-EXPIRY-DATE
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND STATUS = 'AC'
           END-EXEC
      *    SQLCODE 100: policy has no active coverages to extend
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

       8500-ROLLBACK-RENEWAL.
           EXEC CICS SYNCPOINT ROLLBACK
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               EXEC CICS ABEND
                   ABCODE('PRWR')
               END-EXEC
           END-IF
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
