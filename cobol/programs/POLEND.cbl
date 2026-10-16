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
      *           2026-10-16 - Compute pro-rata premium adjustment
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
       01  WS-DAYS-REMAINING         PIC S9(05) COMP-3 VALUE ZERO.
       01  WS-DAYS-IN-TERM           PIC S9(05) COMP-3 VALUE ZERO.
       01  WS-PRORATA-FACTOR         PIC S9(01)V9(06) COMP-3 VALUE ZERO.
       01  WS-PREMIUM-ADJUST         PIC S9(09)V99 COMP-3 VALUE ZERO.
       01  WS-ENDORSEMENT-TYPE        PIC X(03).
           88  END-TYPE-COV-ADD       VALUE 'CAD'.
           88  END-TYPE-COV-REM       VALUE 'CRM'.
           88  END-TYPE-LMT-CHG       VALUE 'LCH'.
           88  END-TYPE-ADDR-CHG      VALUE 'ACH'.
           88  END-TYPE-CANCEL        VALUE 'CAN'.

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
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 5000-APPLY-ENDORSEMENT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 6000-WRITE-AUDIT-TRAIL
               PERFORM 7000-SEND-CONFIRMATION
           ELSE
               PERFORM 8000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           MOVE ZERO TO WS-PREMIUM-ADJUST
           MOVE ZERO TO WS-PRORATA-FACTOR
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
                      TOTAL_PREMIUM
               INTO :POLICY-NUMBER, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-TOTAL-PREMIUM
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
           IF WS-DAYS-IN-TERM NOT > 0
               MOVE 'INVALID POLICY TERM DATES' TO WS-ERROR-MSG
           ELSE
               IF WS-DAYS-REMAINING < 0
                   OR WS-DAYS-REMAINING > WS-DAYS-IN-TERM
                   MOVE 'ENDORSEMENT DATE OUTSIDE POLICY TERM'
                       TO WS-ERROR-MSG
               ELSE
                   COMPUTE WS-PRORATA-FACTOR ROUNDED =
                       WS-DAYS-REMAINING / WS-DAYS-IN-TERM
               END-IF
           END-IF
           IF WS-ERROR-MSG = SPACES
               EVALUATE TRUE
                   WHEN END-TYPE-CANCEL
                       COMPUTE WS-PREMIUM-ADJUST ROUNDED =
                           0 - (POLICY-TOTAL-PREMIUM
                               * WS-PRORATA-FACTOR)
                           ON SIZE ERROR
                               MOVE 'PREMIUM ADJUSTMENT OUT OF RANGE'
                                   TO WS-ERROR-MSG
                       END-COMPUTE
                   WHEN END-TYPE-COV-ADD
                   WHEN END-TYPE-COV-REM
                   WHEN END-TYPE-LMT-CHG
                   WHEN END-TYPE-ADDR-CHG
      *               No rated premium delta is supplied to this
      *               transaction, so no premium change is booked here.
                       MOVE ZERO TO WS-PREMIUM-ADJUST
                   WHEN OTHER
                       MOVE 'INVALID ENDORSEMENT TYPE' TO WS-ERROR-MSG
               END-EVALUATE
           END-IF
           IF WS-ERROR-MSG = SPACES
               IF WS-PREMIUM-ADJUST <
                   0 - POLICY-TOTAL-PREMIUM
                   OR WS-PREMIUM-ADJUST > POLICY-TOTAL-PREMIUM
                   MOVE 'PREMIUM ADJUSTMENT OUT OF RANGE'
                       TO WS-ERROR-MSG
               END-IF
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
           IF WS-ERROR-MSG = SPACES
               COMPUTE POLICY-TOTAL-PREMIUM =
                   POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST
                   ON SIZE ERROR
                       MOVE 'PREMIUM ADJUSTMENT OUT OF RANGE'
                           TO WS-ERROR-MSG
               END-COMPUTE
               IF WS-ERROR-MSG = SPACES
                   EXEC SQL
                       UPDATE POLICIES
                       SET TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                           LAST_UPDATED = CURRENT TIMESTAMP,
                           UPDATED_BY = 'POLEND'
                       WHERE POLICY_NUMBER = :POLICY-NUMBER
                   END-EXEC
                   IF SQLCODE NOT = 0
                       MOVE 'DB2 ERROR UPDATING POLICY PREMIUM'
                           TO WS-ERROR-MSG
                       EXEC CICS SYNCPOINT ROLLBACK END-EXEC
                   END-IF
               END-IF
           END-IF
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
