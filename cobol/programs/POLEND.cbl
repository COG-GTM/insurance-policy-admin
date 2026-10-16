       IDENTIFICATION DIVISION.
       PROGRAM-ID. POLEND.
      ******************************************************************
      * POLEND - Policy Endorsement Processing
      * CICS Transaction: PEND
      * System:   Policy Administration System (PAS)
      * Author:   S. Ramirez
      * Date:     1999-02-10
      * Modified: 2007-04-15 - Added mid-term cancellation
      *           2016-08-01 - Added endorsement audit trail
      *           2026-10-16 - Added RACF authorization (branch/cancel)
      *
      * Processes policy endorsements (changes to existing policy):
      * - Coverage additions/removals
      * - Limit changes
      * - Address changes
      * - Named insured changes
      * Calculates pro-rata premium adjustment.
      ******************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'POLEND'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-ENDORSEMENT-SEQ        PIC 9(05).
       01  WS-DAYS-REMAINING         PIC 9(03).
       01  WS-DAYS-IN-TERM           PIC 9(03).
       01  WS-PRORATA-FACTOR         PIC S9(01)V9(06) COMP-3.
       01  WS-PREMIUM-ADJUST         PIC S9(09)V99 COMP-3.
       01  WS-ENDORSEMENT-TYPE        PIC X(03).
           88  END-TYPE-COV-ADD       VALUE 'CAD'.
           88  END-TYPE-COV-REM       VALUE 'CRM'.
           88  END-TYPE-LMT-CHG       VALUE 'LCH'.
           88  END-TYPE-ADDR-CHG      VALUE 'ACH'.
           88  END-TYPE-CANCEL        VALUE 'CAN'.

       01  WS-BRANCH-IND             PIC S9(04) COMP.
       01  WS-SEC-RESCLASS           PIC X(08) VALUE 'FACILITY'.
       01  WS-SEC-RESID              PIC X(44).
       01  WS-SEC-RESID-LEN          PIC S9(08) COMP.
       01  WS-SEC-PTR                PIC S9(04) COMP.
       01  WS-SEC-UPDATE             PIC S9(08) COMP.
       01  WS-SEC-ACCESS-SW          PIC X(01).
           88  WS-SEC-ACCESS-GRANTED VALUE 'Y'.

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-RECEIVE-ENDORSEMENT
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-VALIDATE-ENDORSEMENT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-PRORATA
               PERFORM 5000-APPLY-ENDORSEMENT
               PERFORM 6000-WRITE-AUDIT-TRAIL
               PERFORM 7000-SEND-CONFIRMATION
           ELSE
               PERFORM 8000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           EXEC CICS ASKTIME ABSTIME(WS-CURRENT-DATE) END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
               YYYYMMDD(WS-CURRENT-DATE)
           END-EXEC
           .

       2000-RECEIVE-ENDORSEMENT.
           EXEC CICS RECEIVE
               MAP('POLEMAP')
               MAPSET('POLEMAPS')
               INTO(POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING ENDORSEMENT DATA'
                   TO WS-ERROR-MSG
           END-IF
      *    Read current policy from DB2
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_STATUS,
                      EFFECTIVE_DATE, EXPIRY_DATE,
                      TOTAL_PREMIUM, BRANCH_CODE
               INTO :POLICY-NUMBER, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-TOTAL-PREMIUM,
                    :POLICY-BRANCH-CODE :WS-BRANCH-IND
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
               WITH RS USE AND KEEP UPDATE LOCKS
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND' TO WS-ERROR-MSG
           END-IF
           .

       3000-VALIDATE-ENDORSEMENT.
           IF NOT POL-STAT-ACTIVE
               MOVE 'ONLY ACTIVE POLICIES CAN BE ENDORSED'
                   TO WS-ERROR-MSG
           END-IF
           IF WS-ENDORSEMENT-TYPE = SPACES
               MOVE 'ENDORSEMENT TYPE IS REQUIRED'
                   TO WS-ERROR-MSG
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3100-AUTHORIZE-ENDORSEMENT
           END-IF
           .

       3100-AUTHORIZE-ENDORSEMENT.
      *    Signed-on user needs UPDATE to the policy's servicing branch
      *    profile; cancellations also need the cancellation profile.
           IF WS-BRANCH-IND < 0 OR POLICY-BRANCH-CODE = SPACES
               MOVE 'NOT AUTHORIZED FOR THIS POLICY BRANCH'
                   TO WS-ERROR-MSG
           ELSE
               MOVE SPACES TO WS-SEC-RESID
               MOVE 1 TO WS-SEC-PTR
               STRING 'PAS.POLEND.BRANCH.' DELIMITED BY SIZE
                      POLICY-BRANCH-CODE DELIMITED BY SPACE
                   INTO WS-SEC-RESID WITH POINTER WS-SEC-PTR
               END-STRING
               COMPUTE WS-SEC-RESID-LEN = WS-SEC-PTR - 1
               PERFORM 3110-CHECK-UPDATE-ACCESS
               IF NOT WS-SEC-ACCESS-GRANTED
                   MOVE 'NOT AUTHORIZED FOR THIS POLICY BRANCH'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           IF WS-ERROR-MSG = SPACES AND END-TYPE-CANCEL
               MOVE 'PAS.POLEND.CANCEL' TO WS-SEC-RESID
               MOVE 17 TO WS-SEC-RESID-LEN
               PERFORM 3110-CHECK-UPDATE-ACCESS
               IF NOT WS-SEC-ACCESS-GRANTED
                   MOVE 'NOT AUTHORIZED TO CANCEL POLICIES'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           .

       3110-CHECK-UPDATE-ACCESS.
           MOVE 'N' TO WS-SEC-ACCESS-SW
           MOVE ZERO TO WS-SEC-UPDATE
           EXEC CICS QUERY SECURITY
               RESCLASS(WS-SEC-RESCLASS)
               RESID(WS-SEC-RESID)
               RESIDLENGTH(WS-SEC-RESID-LEN)
               UPDATE(WS-SEC-UPDATE)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE = DFHRESP(NORMAL)
              AND WS-SEC-UPDATE = DFHVALUE(UPDATABLE)
               MOVE 'Y' TO WS-SEC-ACCESS-SW
           END-IF
           .

       4000-CALCULATE-PRORATA.
      *    Calculate pro-rata factor for premium adjustment
      *    Days remaining / Days in term
           COMPUTE WS-DAYS-IN-TERM =
               FUNCTION INTEGER-OF-DATE(POLICY-EXPIRY-DATE)
             - FUNCTION INTEGER-OF-DATE(POLICY-EFFECTIVE-DATE)
           COMPUTE WS-DAYS-REMAINING =
               FUNCTION INTEGER-OF-DATE(POLICY-EXPIRY-DATE)
             - FUNCTION INTEGER-OF-DATE(WS-CURRENT-DATE)
           IF WS-DAYS-IN-TERM > 0
               COMPUTE WS-PRORATA-FACTOR =
                   WS-DAYS-REMAINING / WS-DAYS-IN-TERM
           ELSE
               MOVE 1 TO WS-PRORATA-FACTOR
           END-IF
           .

       5000-APPLY-ENDORSEMENT.
      *    Get next endorsement sequence
           EXEC SQL
               SELECT COALESCE(MAX(ENDORSEMENT_SEQ), 0) + 1
               INTO :WS-ENDORSEMENT-SEQ
               FROM ENDORSEMENTS
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC

           EXEC SQL
               INSERT INTO ENDORSEMENTS
               (POLICY_NUMBER, ENDORSEMENT_SEQ,
                ENDORSEMENT_TYPE, EFFECTIVE_DATE,
                PREMIUM_ADJUSTMENT, PROCESSED_DATE,
                PROCESSED_BY)
               VALUES
               (:POLICY-NUMBER, :WS-ENDORSEMENT-SEQ,
                :WS-ENDORSEMENT-TYPE, :WS-CURRENT-DATE,
                :WS-PREMIUM-ADJUST, CURRENT TIMESTAMP,
                'POLEND')
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR INSERTING ENDORSEMENT'
                   TO WS-ERROR-MSG
           END-IF

      *    Update policy premium
           COMPUTE POLICY-TOTAL-PREMIUM =
               POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST
           EXEC SQL
               UPDATE POLICIES
               SET TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = 'POLEND'
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           .

       6000-WRITE-AUDIT-TRAIL.
      *    Added 2016 for regulatory compliance
           EXEC CICS WRITEQ TS
               QUEUE('ENDORSEMENT-AUDIT')
               FROM(POLICY-RECORD)
               LENGTH(LENGTH OF POLICY-RECORD)
           END-EXEC
           .

       7000-SEND-CONFIRMATION.
           EXEC CICS SEND
               MAP('POLECONF')
               MAPSET('POLEMAPS')
               FROM(POLICY-RECORD)
               ERASE
           END-EXEC
           .

       8000-SEND-ERROR.
           EXEC CICS SEND TEXT
               FROM(WS-ERROR-MSG)
               LENGTH(79)
               ERASE
           END-EXEC
           .

       9999-RETURN.
           EXEC CICS RETURN
               TRANSID('PEND')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
