       IDENTIFICATION DIVISION.
       PROGRAM-ID. POLNEW.
      ******************************************************************
      * POLNEW - New Policy Creation Program
      * CICS Transaction: PNEW
      * System:   Policy Administration System (PAS)
      * Author:   J. Henderson
      * Date:     1998-04-01
      * Modified: 2003-06-15 - Added terrorism coverage check
      *           2010-09-20 - Added cyber liability option
      *           2022-03-01 - Added API flag for REST facade
      *           2026-09-25 - Bind agent/branch to signed-on user and
      *                        enforce server-side financial bounds
      *
      * This program handles creation of new insurance policies
      * via CICS terminal or BMS map input. Validates customer
      * data, assigns policy number, creates coverage records,
      * and triggers underwriting referral via MQ message.
      ******************************************************************
       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'POLNEW'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-DB2-SQLCODE            PIC S9(09) COMP-5.
       01  WS-CURRENT-DATE           PIC 9(08).
       01  WS-CURRENT-TIME           PIC 9(06).
       01  WS-POLICY-SEQ             PIC 9(10).
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-MQ-QUEUE               PIC X(48)
           VALUE 'ACME.PAS.UNDERWRITING.REQUEST'.
       01  WS-MQ-MSG-LENGTH          PIC S9(08) COMP.
       01  WS-USER-ID                PIC X(08).

       01  WS-AGENT-AUTHORITY.
           05  WS-AUTH-AGENT-CODE    PIC X(06).
           05  WS-AUTH-BRANCH-CODE   PIC X(04).
           05  WS-AUTH-STATUS        PIC X(01).
               88  WS-AUTH-ACTIVE    VALUE 'A'.

       01  WS-PRODUCT-LIMITS.
           05  WS-MIN-PREMIUM        PIC S9(09)V99 COMP-3.
           05  WS-MAX-PREMIUM        PIC S9(09)V99 COMP-3.
           05  WS-MIN-COV-LIMIT      PIC S9(11)V99 COMP-3.
           05  WS-MAX-COV-LIMIT      PIC S9(11)V99 COMP-3.
           05  WS-MAX-DEDUCTIBLE     PIC S9(07)V99 COMP-3.

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.
       COPY CUSTOMER-RECORD.

       01  WS-DB2-TIMESTAMP          PIC X(26).

           EXEC SQL INCLUDE SQLCA END-EXEC.

       01  DCLPOLICIES.
           10  POL-NUM                PIC X(12).
           10  POL-TYP                PIC X(03).
           10  POL-STAT               PIC X(02).
           10  POL-EFF-DT             PIC X(10).
           10  POL-EXP-DT             PIC X(10).
           10  POL-HOLDER             PIC X(10).
           10  POL-AGENT              PIC X(06).
           10  POL-BRANCH             PIC X(04).
           10  POL-TOTAL-PREM         PIC S9(09)V99 COMP-3.
           10  POL-DEDUCT             PIC S9(07)V99 COMP-3.
           10  POL-LMT                PIC S9(11)V99 COMP-3.
           10  POL-INCEPT-DT          PIC X(10).
           10  POL-RENEW-CNT          PIC 9(03).
           10  POL-UW-STAT            PIC X(02).
           10  POL-RISK               PIC 9(03).
           10  POL-WEB                PIC X(01).
           10  POL-API                PIC X(01).
           10  POL-LAST-UPD           PIC X(26).
           10  POL-UPD-BY             PIC X(08).

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-INITIALIZE
           PERFORM 2000-RECEIVE-MAP
           PERFORM 3000-VALIDATE-INPUT
           IF WS-ERROR-MSG = SPACES
               PERFORM 3100-VALIDATE-AUTHORITY
                   THRU 3100-EXIT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3200-VALIDATE-FINANCIALS
                   THRU 3200-EXIT
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 4000-GENERATE-POLICY-NUM
               PERFORM 5000-INSERT-POLICY
               PERFORM 6000-INSERT-COVERAGES
               PERFORM 7000-SEND-MQ-MESSAGE
               PERFORM 8000-SEND-CONFIRMATION
           ELSE
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-INITIALIZE.
           MOVE SPACES TO WS-ERROR-MSG
           MOVE SPACES TO WS-AGENT-AUTHORITY
           MOVE ZEROS  TO WS-MIN-PREMIUM WS-MAX-PREMIUM
                          WS-MIN-COV-LIMIT WS-MAX-COV-LIMIT
                          WS-MAX-DEDUCTIBLE
           EXEC CICS ASKTIME
               ABSTIME(WS-CURRENT-DATE)
           END-EXEC
           EXEC CICS FORMATTIME
               ABSTIME(WS-CURRENT-DATE)
               YYYYMMDD(WS-CURRENT-DATE)
               TIME(WS-CURRENT-TIME)
           END-EXEC
           .

       2000-RECEIVE-MAP.
           EXEC CICS RECEIVE
               MAP('POLNMAP')
               MAPSET('POLNMAPS')
               INTO(POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ERROR RECEIVING MAP DATA' TO WS-ERROR-MSG
           END-IF
           .

       3000-VALIDATE-INPUT.
           IF POLICY-HOLDER-ID = SPACES
               MOVE 'POLICYHOLDER ID IS REQUIRED' TO WS-ERROR-MSG
           END-IF
           IF POLICY-TYPE = SPACES
               MOVE 'POLICY TYPE IS REQUIRED' TO WS-ERROR-MSG
           END-IF
           IF POLICY-EFFECTIVE-DATE < WS-CURRENT-DATE
               MOVE 'EFFECTIVE DATE CANNOT BE IN PAST'
                   TO WS-ERROR-MSG
           END-IF
           IF POLICY-LIMIT = ZEROS
               MOVE 'POLICY LIMIT MUST BE GREATER THAN ZERO'
                   TO WS-ERROR-MSG
           END-IF
      *    Validate policyholder exists in DB2
           EXEC SQL
               SELECT CUST_ID
               INTO :CUST-ID
               FROM POLICY_HOLDERS
               WHERE CUST_ID = :POLICY-HOLDER-ID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'POLICYHOLDER NOT FOUND IN SYSTEM'
                   TO WS-ERROR-MSG
           END-IF
           .

       3100-VALIDATE-AUTHORITY.
      *    The acting user may only write business for the agent and
      *    branch they are registered against in ACMEINS.AGENTS.
           EXEC CICS ASSIGN
               USERID(WS-USER-ID)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'UNABLE TO DETERMINE SIGNED-ON USER'
                   TO WS-ERROR-MSG
               GO TO 3100-EXIT
           END-IF

           EXEC SQL
               SELECT AGENT_CODE, BRANCH_CODE, AGENT_STATUS
               INTO :WS-AUTH-AGENT-CODE,
                    :WS-AUTH-BRANCH-CODE,
                    :WS-AUTH-STATUS
               FROM AGENTS
               WHERE RACF_USER_ID = :WS-USER-ID
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   CONTINUE
               WHEN 100
                   MOVE 'USER NOT REGISTERED TO WRITE NEW BUSINESS'
                       TO WS-ERROR-MSG
                   GO TO 3100-EXIT
               WHEN OTHER
                   MOVE 'DB2 ERROR READING AGENT AUTHORITY'
                       TO WS-ERROR-MSG
                   GO TO 3100-EXIT
           END-EVALUATE

           IF NOT WS-AUTH-ACTIVE
               MOVE 'AGENT APPOINTMENT IS NOT ACTIVE'
                   TO WS-ERROR-MSG
               GO TO 3100-EXIT
           END-IF

           IF POLICY-AGENT-CODE = SPACES
               MOVE WS-AUTH-AGENT-CODE TO POLICY-AGENT-CODE
           END-IF
           IF POLICY-BRANCH-CODE = SPACES
               MOVE WS-AUTH-BRANCH-CODE TO POLICY-BRANCH-CODE
           END-IF

           IF POLICY-AGENT-CODE NOT = WS-AUTH-AGENT-CODE
               MOVE 'NOT AUTHORIZED FOR REQUESTED AGENT CODE'
                   TO WS-ERROR-MSG
               GO TO 3100-EXIT
           END-IF
           IF POLICY-BRANCH-CODE NOT = WS-AUTH-BRANCH-CODE
               MOVE 'NOT AUTHORIZED FOR REQUESTED BRANCH CODE'
                   TO WS-ERROR-MSG
           END-IF
           .
       3100-EXIT.
           EXIT
           .

       3200-VALIDATE-FINANCIALS.
      *    Premium, limit and deductible entered on the map are bounded
      *    by the filed product rules rather than trusted as keyed.
           EXEC SQL
               SELECT MIN_PREMIUM, MAX_PREMIUM,
                      MIN_COVERAGE_LIMIT, MAX_COVERAGE_LIMIT,
                      MAX_DEDUCTIBLE
               INTO :WS-MIN-PREMIUM, :WS-MAX-PREMIUM,
                    :WS-MIN-COV-LIMIT, :WS-MAX-COV-LIMIT,
                    :WS-MAX-DEDUCTIBLE
               FROM PRODUCT_LIMITS
               WHERE POLICY_TYPE = :POLICY-TYPE
                 AND EFFECTIVE_DATE <= CURRENT DATE
                 AND EXPIRY_DATE >= CURRENT DATE
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   CONTINUE
               WHEN 100
                   MOVE 'NO FILED PRODUCT RULES FOR POLICY TYPE'
                       TO WS-ERROR-MSG
                   GO TO 3200-EXIT
               WHEN OTHER
                   MOVE 'DB2 ERROR READING PRODUCT LIMITS'
                       TO WS-ERROR-MSG
                   GO TO 3200-EXIT
           END-EVALUATE

           IF POLICY-LIMIT < WS-MIN-COV-LIMIT
              OR POLICY-LIMIT > WS-MAX-COV-LIMIT
               MOVE 'COVERAGE LIMIT OUTSIDE FILED PRODUCT RANGE'
                   TO WS-ERROR-MSG
               GO TO 3200-EXIT
           END-IF
           IF POLICY-TOTAL-PREMIUM < WS-MIN-PREMIUM
              OR POLICY-TOTAL-PREMIUM > WS-MAX-PREMIUM
               MOVE 'TOTAL PREMIUM OUTSIDE FILED PRODUCT RANGE'
                   TO WS-ERROR-MSG
               GO TO 3200-EXIT
           END-IF
           IF POLICY-DEDUCTIBLE < ZERO
              OR POLICY-DEDUCTIBLE > WS-MAX-DEDUCTIBLE
               MOVE 'DEDUCTIBLE OUTSIDE FILED PRODUCT RANGE'
                   TO WS-ERROR-MSG
               GO TO 3200-EXIT
           END-IF
           IF POLICY-DEDUCTIBLE >= POLICY-LIMIT
               MOVE 'DEDUCTIBLE MUST BE LESS THAN COVERAGE LIMIT'
                   TO WS-ERROR-MSG
           END-IF
           .
       3200-EXIT.
           EXIT
           .

       4000-GENERATE-POLICY-NUM.
      *    Get next policy sequence from DB2 sequence
           EXEC SQL
               SELECT NEXT VALUE FOR POLICY_SEQ
               INTO :WS-POLICY-SEQ
               FROM SYSIBM.SYSDUMMY1
           END-EXEC
           STRING 'POL' WS-POLICY-SEQ
               DELIMITED BY SIZE
               INTO POLICY-NUMBER
           .

       5000-INSERT-POLICY.
           MOVE POLICY-NUMBER      TO POL-NUM
           MOVE POLICY-TYPE        TO POL-TYP
           MOVE 'PN'               TO POL-STAT
           MOVE POLICY-HOLDER-ID   TO POL-HOLDER
           MOVE WS-AUTH-AGENT-CODE  TO POL-AGENT
           MOVE WS-AUTH-BRANCH-CODE TO POL-BRANCH
           MOVE POLICY-TOTAL-PREMIUM TO POL-TOTAL-PREM
           MOVE POLICY-DEDUCTIBLE  TO POL-DEDUCT
           MOVE POLICY-LIMIT       TO POL-LMT
           MOVE 0                  TO POL-RENEW-CNT
           MOVE 'PN'               TO POL-UW-STAT
           MOVE 0                  TO POL-RISK
           MOVE 'N'                TO POL-WEB
           MOVE 'N'                TO POL-API
           MOVE WS-USER-ID         TO POL-UPD-BY

           EXEC SQL
               INSERT INTO POLICIES
               (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                EFFECTIVE_DATE, EXPIRY_DATE,
                POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE,
                TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT,
                INCEPTION_DATE, RENEWAL_COUNT,
                UW_STATUS, RISK_SCORE, WEB_INDICATOR,
                API_FLAG, LAST_UPDATED, UPDATED_BY)
               VALUES
               (:POL-NUM, :POL-TYP, :POL-STAT,
                :POL-EFF-DT, :POL-EXP-DT,
                :POL-HOLDER, :POL-AGENT, :POL-BRANCH,
                :POL-TOTAL-PREM, :POL-DEDUCT, :POL-LMT,
                :POL-INCEPT-DT, :POL-RENEW-CNT,
                :POL-UW-STAT, :POL-RISK, :POL-WEB,
                :POL-API, CURRENT TIMESTAMP, :POL-UPD-BY)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'DB2 ERROR INSERTING POLICY RECORD'
                   TO WS-ERROR-MSG
               PERFORM 9000-SEND-ERROR
               PERFORM 9999-RETURN
           END-IF
           .

       6000-INSERT-COVERAGES.
      *    Insert default coverages based on policy type
      *    TODO: This should be table-driven but never got refactored
           IF POL-TYPE-AUTO
               MOVE 'AUTL' TO COV-TYPE-CODE
               MOVE 'Auto Liability' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
               MOVE 'AUTP' TO COV-TYPE-CODE
               MOVE 'Auto Physical Damage' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
           END-IF
           IF POL-TYPE-HOME
               MOVE 'PROP' TO COV-TYPE-CODE
               MOVE 'Dwelling Coverage' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
               MOVE 'LIAB' TO COV-TYPE-CODE
               MOVE 'Personal Liability' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
           END-IF
           IF POL-TYPE-COMM
               MOVE 'PROP' TO COV-TYPE-CODE
               MOVE 'Commercial Property' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
               MOVE 'LIAB' TO COV-TYPE-CODE
               MOVE 'General Liability' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
               MOVE 'WKCP' TO COV-TYPE-CODE
               MOVE 'Workers Compensation' TO COV-DESCRIPTION
               PERFORM 6100-WRITE-COVERAGE
           END-IF
           .

       6100-WRITE-COVERAGE.
           ADD 1 TO COV-SEQUENCE-NUM
           MOVE POLICY-NUMBER TO COV-POLICY-NUMBER
           EXEC SQL
               INSERT INTO COVERAGES
               (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE,
                DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE,
                PREMIUM, EFFECTIVE_DATE, EXPIRY_DATE, STATUS)
               VALUES
               (:COV-POLICY-NUMBER, :COV-SEQUENCE-NUM,
                :COV-TYPE-CODE, :COV-DESCRIPTION,
                :COV-LIMIT, :COV-DEDUCTIBLE,
                :COV-PREMIUM, :POL-EFF-DT,
                :POL-EXP-DT, 'AC')
           END-EXEC
           .

       7000-SEND-MQ-MESSAGE.
      *    Send underwriting referral message to MQ queue
           MOVE LENGTH OF POLICY-RECORD TO WS-MQ-MSG-LENGTH
           EXEC CICS WRITEQ TD
               QUEUE(WS-MQ-QUEUE)
               FROM(POLICY-RECORD)
               LENGTH(WS-MQ-MSG-LENGTH)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
      *        Non-fatal: log error but continue
               CONTINUE
           END-IF
           .

       8000-SEND-CONFIRMATION.
           EXEC CICS SEND
               MAP('POLNCONF')
               MAPSET('POLNMAPS')
               FROM(POLICY-RECORD)
               ERASE
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           .

       9000-SEND-ERROR.
           EXEC CICS SEND
               TEXT
               FROM(WS-ERROR-MSG)
               LENGTH(79)
               ERASE
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           .

       9999-RETURN.
           EXEC CICS RETURN
               TRANSID('PNEW')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
