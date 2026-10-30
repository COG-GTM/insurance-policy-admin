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
      *           2026-10-30 - Operator identity, policy scope check,
      *                        durable DB2 audit (POLICY_AUDIT_LOG)
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
       01  WS-USER-ID                PIC X(08).
       01  WS-TERMINAL-ID            PIC X(04).
       01  WS-TRANSACTION-ID         PIC X(04).
       01  WS-NOT-IN-SCOPE-MSG       PIC X(79) VALUE
           'POLICY NOT FOUND OR NOT IN YOUR BOOK OF BUSINESS'.
       01  WS-OLD-PREMIUM            PIC S9(09)V99 COMP-3.

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-RECEIVE-ENDORSEMENT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-VALIDATE-ENDORSEMENT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-CALCULATE-PRORATA
               PERFORM 5000-APPLY-ENDORSEMENT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 6000-WRITE-AUDIT-TRAIL
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 7000-SEND-CONFIRMATION
           ELSE
               PERFORM 8500-ROLLBACK
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
           ELSE
               PERFORM 2100-READ-POLICY-IN-SCOPE
           END-IF
           .

       2100-READ-POLICY-IN-SCOPE.
      *    Read current policy from DB2, only if it is in the
      *    operator's book of business (branch / agent entitlement)
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_STATUS,
                      EFFECTIVE_DATE, EXPIRY_DATE,
                      TOTAL_PREMIUM
               INTO :POLICY-NUMBER, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-TOTAL-PREMIUM
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
               WITH RS USE AND KEEP UPDATE LOCKS
           END-EXEC
           EVALUATE TRUE
               WHEN SQLCODE = 0
                   MOVE POLICY-TOTAL-PREMIUM TO WS-OLD-PREMIUM
               WHEN SQLCODE = 100
                   MOVE WS-NOT-IN-SCOPE-MSG TO WS-ERROR-MSG
               WHEN OTHER
                   MOVE 'DB2 ERROR READING POLICY' TO WS-ERROR-MSG
           END-EVALUATE
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
                :WS-USER-ID)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR INSERTING ENDORSEMENT'
                   TO WS-ERROR-MSG
           ELSE
               PERFORM 5100-UPDATE-POLICY-PREMIUM
           END-IF
           .

       5100-UPDATE-POLICY-PREMIUM.
           COMPUTE POLICY-TOTAL-PREMIUM =
               POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST
           EXEC SQL
               UPDATE POLICIES P
               SET TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
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
                   MOVE 'DB2 ERROR UPDATING POLICY PREMIUM'
                       TO WS-ERROR-MSG
           END-EVALUATE
           .

       6000-WRITE-AUDIT-TRAIL.
      *    Regulatory audit record (2016), now a durable DB2 row in the
      *    same unit of work as the endorsement: who, where, what, how
      *    much. Replaces the temporary storage queue write.
           EXEC SQL
               INSERT INTO POLICY_AUDIT_LOG
               (POLICY_NUMBER, ACTION_CODE, TRANSACTION_ID,
                PROGRAM_ID, USER_ID, TERMINAL_ID,
                CHANGE_SEQ, CHANGE_CODE, OLD_PREMIUM,
                NEW_PREMIUM, PREMIUM_ADJUSTMENT,
                CREATED_TIMESTAMP)
               VALUES
               (:POLICY-NUMBER, 'END', :WS-TRANSACTION-ID,
                :WS-PROGRAM-ID, :WS-USER-ID, :WS-TERMINAL-ID,
                :WS-ENDORSEMENT-SEQ, :WS-ENDORSEMENT-TYPE,
                :WS-OLD-PREMIUM, :POLICY-TOTAL-PREMIUM,
                :WS-PREMIUM-ADJUST, CURRENT TIMESTAMP)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR WRITING AUDIT RECORD - NOT APPLIED'
                   TO WS-ERROR-MSG
           END-IF
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

       8500-ROLLBACK.
      *    Back out any partial change so nothing unaudited commits
           EXEC CICS SYNCPOINT ROLLBACK
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           .

       9999-RETURN.
           EXEC CICS RETURN
               TRANSID('PEND')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
