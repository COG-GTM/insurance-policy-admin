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
      *           2026-09-25 - Object-level authorization on the target
      *                        policy (USER_AUTHORITY)
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
           88  END-TYPE-VALID         VALUE 'CAD' 'CRM' 'LCH'
                                            'ACH' 'CAN'.
       01  WS-USER-ID                PIC X(08).
       01  WS-AUTH-COUNT             PIC S9(04) COMP.
       01  WS-REQUESTED-ADJUST       PIC S9(09)V99 COMP-3.

      *    Endorsement request as keyed on the PEND map
       01  WS-ENDORSEMENT-INPUT.
           05  ENDI-POLICY-NUMBER     PIC X(12).
           05  ENDI-ENDORSEMENT-TYPE  PIC X(03).
           05  ENDI-PREMIUM-ADJUST    PIC S9(09)V99 COMP-3.

       01  WS-AUDIT-RECORD.
           05  AUD-POLICY-NUMBER      PIC X(12).
           05  AUD-USER-ID            PIC X(08).
           05  AUD-ENDORSEMENT-TYPE   PIC X(03).
           05  AUD-ENDORSEMENT-SEQ    PIC 9(05).
           05  AUD-PREMIUM-ADJUST     PIC S9(09)V99 COMP-3.
           05  AUD-PROCESSED-DATE     PIC 9(08).

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
               PERFORM 2500-CHECK-AUTHORIZATION
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 2700-READ-POLICY-FOR-UPDATE
           END-IF
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
           EXEC CICS ASSIGN USERID(WS-USER-ID) END-EXEC
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
               INTO(WS-ENDORSEMENT-INPUT)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING ENDORSEMENT DATA'
                   TO WS-ERROR-MSG
           ELSE
               MOVE ENDI-POLICY-NUMBER    TO POLICY-NUMBER
               MOVE ENDI-ENDORSEMENT-TYPE TO WS-ENDORSEMENT-TYPE
               MOVE ENDI-PREMIUM-ADJUST   TO WS-REQUESTED-ADJUST
               MOVE ZEROS                 TO WS-PREMIUM-ADJUST
           END-IF
           .

       2500-CHECK-AUTHORIZATION.
      *    Object-level authorization: the signed-on user must hold an
      *    entitlement covering the target policy's agent or branch
      *    before the policy is read for update or changed.
           EXEC SQL
               SELECT AGENT_CODE, BRANCH_CODE
               INTO :POLICY-AGENT-CODE, :POLICY-BRANCH-CODE
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICY NOT FOUND' TO WS-ERROR-MSG
           ELSE
               EXEC SQL
                   SELECT COUNT(*)
                   INTO :WS-AUTH-COUNT
                   FROM USER_AUTHORITY
                   WHERE USER_ID = :WS-USER-ID
                     AND (SCOPE_TYPE = 'X'
                       OR (SCOPE_TYPE = 'A'
                           AND SCOPE_VALUE = :POLICY-AGENT-CODE)
                       OR (SCOPE_TYPE = 'B'
                           AND SCOPE_VALUE = :POLICY-BRANCH-CODE))
               END-EXEC
               IF SQLCODE NOT = 0 OR WS-AUTH-COUNT = 0
                   MOVE 'NOT AUTHORIZED TO ENDORSE THIS POLICY'
                       TO WS-ERROR-MSG
               END-IF
           END-IF
           .

       2700-READ-POLICY-FOR-UPDATE.
      *    Read current policy from DB2. The entitlement predicate is
      *    repeated here so the update lock is never taken on a policy
      *    the user is not entitled to.
           EXEC SQL
               SELECT P.POLICY_NUMBER, P.POLICY_STATUS,
                      P.EFFECTIVE_DATE, P.EXPIRY_DATE,
                      P.TOTAL_PREMIUM
               INTO :POLICY-NUMBER, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-TOTAL-PREMIUM
               FROM POLICIES P
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
                 AND EXISTS
                     (SELECT 1 FROM USER_AUTHORITY U
                       WHERE U.USER_ID = :WS-USER-ID
                         AND (U.SCOPE_TYPE = 'X'
                           OR (U.SCOPE_TYPE = 'A'
                               AND U.SCOPE_VALUE = P.AGENT_CODE)
                           OR (U.SCOPE_TYPE = 'B'
                               AND U.SCOPE_VALUE = P.BRANCH_CODE)))
               WITH RS USE AND KEEP UPDATE LOCKS
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'NOT AUTHORIZED TO ENDORSE THIS POLICY'
                   TO WS-ERROR-MSG
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
           ELSE
               IF NOT END-TYPE-VALID
                   MOVE 'INVALID ENDORSEMENT TYPE'
                       TO WS-ERROR-MSG
               END-IF
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
           COMPUTE WS-PREMIUM-ADJUST ROUNDED =
               WS-REQUESTED-ADJUST * WS-PRORATA-FACTOR
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
               EXEC CICS SYNCPOINT ROLLBACK END-EXEC
               PERFORM 8000-SEND-ERROR
               PERFORM 9999-RETURN
           END-IF

      *    Update policy premium. The entitlement predicate is part of
      *    the WHERE clause so the row can only be changed by a user
      *    entitled to it.
           COMPUTE POLICY-TOTAL-PREMIUM =
               POLICY-TOTAL-PREMIUM + WS-PREMIUM-ADJUST
           EXEC SQL
               UPDATE POLICIES P
               SET TOTAL_PREMIUM = :POLICY-TOTAL-PREMIUM,
                   LAST_UPDATED = CURRENT TIMESTAMP,
                   UPDATED_BY = :WS-USER-ID
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
                 AND EXISTS
                     (SELECT 1 FROM USER_AUTHORITY U
                       WHERE U.USER_ID = :WS-USER-ID
                         AND (U.SCOPE_TYPE = 'X'
                           OR (U.SCOPE_TYPE = 'A'
                               AND U.SCOPE_VALUE = P.AGENT_CODE)
                           OR (U.SCOPE_TYPE = 'B'
                               AND U.SCOPE_VALUE = P.BRANCH_CODE)))
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR UPDATING POLICY PREMIUM'
                   TO WS-ERROR-MSG
               EXEC CICS SYNCPOINT ROLLBACK END-EXEC
               PERFORM 8000-SEND-ERROR
               PERFORM 9999-RETURN
           END-IF
           .

       6000-WRITE-AUDIT-TRAIL.
      *    Added 2016 for regulatory compliance
           MOVE POLICY-NUMBER        TO AUD-POLICY-NUMBER
           MOVE WS-USER-ID           TO AUD-USER-ID
           MOVE WS-ENDORSEMENT-TYPE  TO AUD-ENDORSEMENT-TYPE
           MOVE WS-ENDORSEMENT-SEQ   TO AUD-ENDORSEMENT-SEQ
           MOVE WS-PREMIUM-ADJUST    TO AUD-PREMIUM-ADJUST
           MOVE WS-CURRENT-DATE      TO AUD-PROCESSED-DATE
           EXEC CICS WRITEQ TS
               QUEUE('ENDORSEMENT-AUDIT')
               FROM(WS-AUDIT-RECORD)
               LENGTH(LENGTH OF WS-AUDIT-RECORD)
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
