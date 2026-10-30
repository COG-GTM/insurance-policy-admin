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
      *           2026-10-30 - UW referral via MQPUT1; failure is
      *                        fatal and backs out the new policy
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
       01  WS-MQ-HCONN               PIC S9(09) BINARY VALUE 0.
       01  WS-MQ-COMPCODE            PIC S9(09) BINARY.
       01  WS-MQ-REASON              PIC S9(09) BINARY.
       01  WS-LOG-QUEUE              PIC X(04) VALUE 'CSMT'.
       01  WS-LOG-MSG.
           05  FILLER                PIC X(08) VALUE 'POLNEW: '.
           05  WS-LOG-POLICY         PIC X(12).
           05  FILLER                PIC X(30)
               VALUE ' UW REFERRAL MQPUT1 FAILED CC='.
           05  WS-LOG-COMPCODE       PIC 9(04).
           05  FILLER                PIC X(04) VALUE ' RC='.
           05  WS-LOG-REASON         PIC 9(04).
       01  WS-LOG-LENGTH             PIC S9(04) COMP.

       01  MQM-OBJECT-DESCRIPTOR.
           COPY CMQODV.
       01  MQM-MESSAGE-DESCRIPTOR.
           COPY CMQMDV.
       01  MQM-PUT-MESSAGE-OPTIONS.
           COPY CMQPMOV.
       01  MQM-CONSTANTS.
           COPY CMQV.

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
           MOVE POLICY-AGENT-CODE  TO POL-AGENT
           MOVE POLICY-BRANCH-CODE TO POL-BRANCH
           MOVE POLICY-TOTAL-PREMIUM TO POL-TOTAL-PREM
           MOVE POLICY-DEDUCTIBLE  TO POL-DEDUCT
           MOVE POLICY-LIMIT       TO POL-LMT
           MOVE 0                  TO POL-RENEW-CNT
           MOVE 'PN'               TO POL-UW-STAT
           MOVE 0                  TO POL-RISK
           MOVE 'N'                TO POL-WEB
           MOVE 'N'                TO POL-API
           MOVE 'POLNEW'           TO POL-UPD-BY

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
      *    Put the underwriting referral on the MQ request queue in
      *    the task's unit of work. A policy must not exist without
      *    its referral, so any failure backs out the new policy.
           MOVE WS-MQ-QUEUE        TO MQOD-OBJECTNAME
           MOVE MQOT-Q             TO MQOD-OBJECTTYPE
           MOVE MQMT-DATAGRAM      TO MQMD-MSGTYPE
           MOVE MQFMT-NONE         TO MQMD-FORMAT
           MOVE MQPER-PERSISTENT   TO MQMD-PERSISTENCE
           COMPUTE MQPMO-OPTIONS = MQPMO-SYNCPOINT
                                 + MQPMO-FAIL-IF-QUIESCING
                                 + MQPMO-NEW-MSG-ID
                                 + MQPMO-NEW-CORREL-ID
           MOVE LENGTH OF POLICY-RECORD TO WS-MQ-MSG-LENGTH
           CALL 'MQPUT1' USING WS-MQ-HCONN
                               MQM-OBJECT-DESCRIPTOR
                               MQM-MESSAGE-DESCRIPTOR
                               MQM-PUT-MESSAGE-OPTIONS
                               WS-MQ-MSG-LENGTH
                               POLICY-RECORD
                               WS-MQ-COMPCODE
                               WS-MQ-REASON
           IF WS-MQ-COMPCODE NOT = MQCC-OK
               PERFORM 7100-REFERRAL-FAILED
           END-IF
           .

       7100-REFERRAL-FAILED.
           MOVE POLICY-NUMBER      TO WS-LOG-POLICY
           MOVE WS-MQ-COMPCODE     TO WS-LOG-COMPCODE
           MOVE WS-MQ-REASON       TO WS-LOG-REASON
           MOVE LENGTH OF WS-LOG-MSG TO WS-LOG-LENGTH
           EXEC CICS WRITEQ TD
               QUEUE(WS-LOG-QUEUE)
               FROM(WS-LOG-MSG)
               LENGTH(WS-LOG-LENGTH)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           EXEC CICS SYNCPOINT ROLLBACK
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           MOVE 'UNDERWRITING REFERRAL FAILED - POLICY NOT CREATED'
               TO WS-ERROR-MSG
           PERFORM 9000-SEND-ERROR
           PERFORM 9999-RETURN
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
