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
      *           2026-10-16 - Enforce endorsement type, cancel
      *           confirm, agent authorization
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
       01  WS-ENDORSEMENT-TYPE        PIC X(03) VALUE SPACES.
           88  END-TYPE-VALID         VALUE 'CAD' 'CRM' 'LCH'
                                            'ACH' 'CAN'.
           88  END-TYPE-COV-ADD       VALUE 'CAD'.
           88  END-TYPE-COV-REM       VALUE 'CRM'.
           88  END-TYPE-LMT-CHG       VALUE 'LCH'.
           88  END-TYPE-ADDR-CHG      VALUE 'ACH'.
           88  END-TYPE-CANCEL        VALUE 'CAN'.

       01  WS-AGENT-RESCLASS         PIC X(08) VALUE 'PASAGENT'.
       01  WS-AGENT-UPDATE-CVDA      PIC S9(08) COMP.
      *    Symbolic input map for POLEMAP (DFHMSD TIOAPFX=YES);
      *    must match the assembled POLEMAPS mapset
       01  POLEMAPI.
           02  FILLER                 PIC X(12).
           02  PENUML                 PIC S9(04) COMP.
           02  PENUMF                 PIC X(01).
           02  PENUMI                 PIC X(12).
           02  PETYPL                 PIC S9(04) COMP.
           02  PETYPF                 PIC X(01).
           02  PETYPI                 PIC X(03).
           02  PECNFL                 PIC S9(04) COMP.
           02  PECNFF                 PIC X(01).
           02  PECNFI                 PIC X(01).
               88  IN-CANCEL-CONFIRMED VALUE 'Y'.

       01  WS-NULL-INDICATORS.
           05  WS-AGENT-IND           PIC S9(04) COMP.
           05  WS-BRANCH-IND          PIC S9(04) COMP.
           05  WS-PREM-IND            PIC S9(04) COMP.
           05  WS-DEDUCT-IND          PIC S9(04) COMP.
           05  WS-LIMIT-IND           PIC S9(04) COMP.
           05  WS-INCEPT-IND          PIC S9(04) COMP.
           05  WS-RENEW-IND           PIC S9(04) COMP.
           05  WS-UWSTAT-IND          PIC S9(04) COMP.
           05  WS-RISK-IND            PIC S9(04) COMP.
           05  WS-WEB-IND             PIC S9(04) COMP.
           05  WS-API-IND             PIC S9(04) COMP.

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
           MOVE SPACES TO WS-ENDORSEMENT-TYPE
           MOVE LOW-VALUES TO POLEMAPI
           INITIALIZE POLICY-RECORD
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
               INTO(POLEMAPI)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING ENDORSEMENT DATA'
                   TO WS-ERROR-MSG
           ELSE
               MOVE PENUMI TO POLICY-NUMBER
               MOVE PETYPI TO WS-ENDORSEMENT-TYPE
           END-IF
      *    Read current policy from DB2; null indicators keep a
      *    NULL column from tripping -305; a NULL TOTAL_PREMIUM
      *    is still rejected below since it cannot be endorsed
           IF WS-ERROR-MSG = SPACES
               EXEC SQL
                   SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                          EFFECTIVE_DATE, EXPIRY_DATE,
                          POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE,
                          TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT,
                          INCEPTION_DATE, RENEWAL_COUNT,
                          UW_STATUS, RISK_SCORE,
                          WEB_INDICATOR, API_FLAG
                   INTO :POLICY-NUMBER, :POLICY-TYPE,
                        :POLICY-STATUS,
                        :POLICY-EFFECTIVE-DATE,
                        :POLICY-EXPIRY-DATE,
                        :POLICY-HOLDER-ID,
                        :POLICY-AGENT-CODE :WS-AGENT-IND,
                        :POLICY-BRANCH-CODE :WS-BRANCH-IND,
                        :POLICY-TOTAL-PREMIUM :WS-PREM-IND,
                        :POLICY-DEDUCTIBLE :WS-DEDUCT-IND,
                        :POLICY-LIMIT :WS-LIMIT-IND,
                        :POLICY-INCEPTION-DATE :WS-INCEPT-IND,
                        :POLICY-RENEWAL-COUNT :WS-RENEW-IND,
                        :POLICY-UW-STATUS :WS-UWSTAT-IND,
                        :POLICY-RISK-SCORE :WS-RISK-IND,
                        :POLICY-WEB-IND :WS-WEB-IND,
                        :POLICY-API-FLAG :WS-API-IND
                   FROM POLICIES
                   WHERE POLICY_NUMBER = :POLICY-NUMBER
                   WITH RS USE AND KEEP UPDATE LOCKS
               END-EXEC
               IF SQLCODE = 100
                   MOVE 'POLICY NOT FOUND' TO WS-ERROR-MSG
               ELSE
                   IF SQLCODE NOT = 0
                       MOVE 'DB2 ERROR READING POLICY'
                           TO WS-ERROR-MSG
                   ELSE
                       IF WS-PREM-IND < 0
                           MOVE 'POLICY PREMIUM NOT AVAILABLE'
                               TO WS-ERROR-MSG
                       END-IF
                   END-IF
               END-IF
           END-IF
           .

       3000-VALIDATE-ENDORSEMENT.
           IF NOT POL-STAT-ACTIVE
               MOVE 'ONLY ACTIVE POLICIES CAN BE ENDORSED'
                   TO WS-ERROR-MSG
           END-IF
           IF NOT END-TYPE-VALID
               MOVE 'INVALID ENDORSEMENT TYPE'
                   TO WS-ERROR-MSG
           END-IF
           IF END-TYPE-CANCEL AND NOT IN-CANCEL-CONFIRMED
               MOVE 'CANCELLATION REQUIRES CONFIRMATION'
                   TO WS-ERROR-MSG
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3100-CHECK-AUTHORIZATION
           END-IF
           .

       3100-CHECK-AUTHORIZATION.
      *    Signed-on user must hold UPDATE on the policy's agent
      *    profile in RACF class PASAGENT
           IF POLICY-AGENT-CODE = SPACES OR LOW-VALUES
               MOVE 'NOT AUTHORIZED FOR THIS POLICY'
                   TO WS-ERROR-MSG
           ELSE
               EXEC CICS QUERY SECURITY
                   RESCLASS(WS-AGENT-RESCLASS)
                   RESID(POLICY-AGENT-CODE)
                   RESIDLENGTH(LENGTH OF POLICY-AGENT-CODE)
                   UPDATE(WS-AGENT-UPDATE-CVDA)
                   RESP(WS-RESPONSE-CODE)
               END-EXEC
               IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
                  OR WS-AGENT-UPDATE-CVDA NOT = DFHVALUE(UPDATABLE)
                   MOVE 'NOT AUTHORIZED FOR THIS POLICY'
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
