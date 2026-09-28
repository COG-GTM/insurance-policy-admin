       IDENTIFICATION DIVISION.
       PROGRAM-ID. POLQRY.
      ******************************************************************
      * POLQRY - Policy Inquiry Program
      * CICS Transaction: PQRY
      * System:   Policy Administration System (PAS)
      * Author:   J. Henderson
      * Date:     1998-04-15
      * Modified: 2008-01-10 - Added coverage detail display
      *           2022-04-01 - Added API flag check for facade
      *
      * Read-only policy inquiry. Displays policy header, coverages,
      * premium details, and underwriting status on BMS maps.
      * Used by CSRs and underwriters for policy lookup.
      *
      * Access is restricted to policies within the signed-on user's
      * book of business (USER_POLICY_ACCESS agent/branch grants).
      * Policyholder phone/email are only read for users granted
      * PII access.
      ******************************************************************
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.

       01  WS-PROGRAM-ID             PIC X(08) VALUE 'POLQRY'.
       01  WS-COMMAREA-LENGTH        PIC S9(04) COMP VALUE 256.
       01  WS-RESPONSE-CODE          PIC S9(08) COMP.
       01  WS-ERROR-MSG              PIC X(79).
       01  WS-COV-COUNT              PIC 9(03).
       01  WS-DISPLAY-LINE           PIC X(80).
       01  WS-USER-ID                PIC X(08).
       01  WS-GRANT-COUNT            PIC S9(09) COMP.
       01  WS-PII-ACCESS             PIC X(01).
           88  WS-PII-ALLOWED        VALUE 'Y'.

       COPY POLICY-RECORD.
       COPY COVERAGE-RECORD.
       COPY CUSTOMER-RECORD.
       COPY PREMIUM-RECORD.

       01  WS-COVERAGE-TABLE.
           05  WS-COV-ENTRY OCCURS 20 TIMES.
               10  WS-COV-TYPE        PIC X(04).
               10  WS-COV-DESC        PIC X(40).
               10  WS-COV-LIMIT       PIC S9(11)V99 COMP-3.
               10  WS-COV-PREMIUM     PIC S9(09)V99 COMP-3.
               10  WS-COV-STATUS      PIC X(02).

           EXEC SQL INCLUDE SQLCA END-EXEC.

       LINKAGE SECTION.
       01  DFHCOMMAREA               PIC X(256).

       PROCEDURE DIVISION.
       0000-MAIN-LOGIC.
           PERFORM 1000-RECEIVE-INPUT
           IF WS-ERROR-MSG = SPACES
               PERFORM 1500-AUTHORIZE-USER
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 2000-READ-POLICY
           END-IF
           IF WS-ERROR-MSG = SPACES
               PERFORM 3000-READ-CUSTOMER
               PERFORM 4000-READ-COVERAGES
               PERFORM 5000-DISPLAY-POLICY
           ELSE
               PERFORM 9000-SEND-ERROR
           END-IF
           PERFORM 9999-RETURN
           .

       1000-RECEIVE-INPUT.
           MOVE SPACES TO WS-ERROR-MSG
           EXEC CICS RECEIVE
               MAP('POLQMAP')
               MAPSET('POLQMAPS')
               INTO(POLICY-RECORD)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
               MOVE 'ENTER A POLICY NUMBER TO SEARCH'
                   TO WS-ERROR-MSG
           END-IF
           IF POLICY-NUMBER = SPACES
               MOVE 'POLICY NUMBER IS REQUIRED' TO WS-ERROR-MSG
           END-IF
           .

       1500-AUTHORIZE-USER.
           MOVE SPACES TO WS-USER-ID
           MOVE 'N' TO WS-PII-ACCESS
           EXEC CICS ASSIGN
               USERID(WS-USER-ID)
               RESP(WS-RESPONSE-CODE)
           END-EXEC
           IF WS-RESPONSE-CODE NOT = DFHRESP(NORMAL)
              OR WS-USER-ID = SPACES
               MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                   TO WS-ERROR-MSG
           ELSE
               EXEC SQL
                   SELECT COUNT(*), COALESCE(MAX(PII_ACCESS), 'N')
                   INTO :WS-GRANT-COUNT, :WS-PII-ACCESS
                   FROM USER_POLICY_ACCESS
                   WHERE USER_ID = :WS-USER-ID
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'DB2 ERROR CHECKING AUTHORIZATION'
                       TO WS-ERROR-MSG
               ELSE
                   IF WS-GRANT-COUNT = 0
                       MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                           TO WS-ERROR-MSG
                   END-IF
               END-IF
           END-IF
           .

       2000-READ-POLICY.
           EXEC SQL
               SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
                      EFFECTIVE_DATE, EXPIRY_DATE,
                      POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE,
                      TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT,
                      INCEPTION_DATE, RENEWAL_COUNT,
                      UW_STATUS, RISK_SCORE,
                      WEB_INDICATOR, API_FLAG
               INTO :POLICY-NUMBER, :POLICY-TYPE, :POLICY-STATUS,
                    :POLICY-EFFECTIVE-DATE, :POLICY-EXPIRY-DATE,
                    :POLICY-HOLDER-ID, :POLICY-AGENT-CODE,
                    :POLICY-BRANCH-CODE,
                    :POLICY-TOTAL-PREMIUM, :POLICY-DEDUCTIBLE,
                    :POLICY-LIMIT,
                    :POLICY-INCEPTION-DATE, :POLICY-RENEWAL-COUNT,
                    :POLICY-UW-STATUS, :POLICY-RISK-SCORE,
                    :POLICY-WEB-IND, :POLICY-API-FLAG
               FROM POLICIES P
               WHERE P.POLICY_NUMBER = :POLICY-NUMBER
                 AND EXISTS
                     (SELECT 1
                        FROM USER_POLICY_ACCESS A
                       WHERE A.USER_ID = :WS-USER-ID
                         AND ((A.SCOPE_TYPE = 'A'
                               AND A.SCOPE_CODE = P.AGENT_CODE)
                           OR (A.SCOPE_TYPE = 'B'
                               AND A.SCOPE_CODE = P.BRANCH_CODE)
                           OR  A.SCOPE_TYPE = 'G'))
           END-EXEC
           IF SQLCODE = 100
               MOVE 'POLICY NOT FOUND' TO WS-ERROR-MSG
           END-IF
           IF SQLCODE < 0
               MOVE 'DB2 ERROR READING POLICY' TO WS-ERROR-MSG
           END-IF
           .

       3000-READ-CUSTOMER.
           MOVE SPACES TO CUST-PHONE CUST-EMAIL
           IF WS-PII-ALLOWED
               EXEC SQL
                   SELECT CUST_ID, CUST_TYPE,
                          LAST_NAME, FIRST_NAME,
                          COMPANY_NAME, PHONE, EMAIL
                   INTO :CUST-ID, :CUST-TYPE,
                        :CUST-LAST-NAME, :CUST-FIRST-NAME,
                        :CUST-COMPANY-NAME, :CUST-PHONE,
                        :CUST-EMAIL
                   FROM POLICY_HOLDERS
                   WHERE CUST_ID = :POLICY-HOLDER-ID
               END-EXEC
           ELSE
               EXEC SQL
                   SELECT CUST_ID, CUST_TYPE,
                          LAST_NAME, FIRST_NAME,
                          COMPANY_NAME
                   INTO :CUST-ID, :CUST-TYPE,
                        :CUST-LAST-NAME, :CUST-FIRST-NAME,
                        :CUST-COMPANY-NAME
                   FROM POLICY_HOLDERS
                   WHERE CUST_ID = :POLICY-HOLDER-ID
               END-EXEC
           END-IF
           .

       4000-READ-COVERAGES.
           MOVE 0 TO WS-COV-COUNT
           EXEC SQL
               DECLARE COV_CURSOR CURSOR FOR
               SELECT COVERAGE_TYPE, DESCRIPTION,
                      COVERAGE_LIMIT, PREMIUM, STATUS
               FROM COVERAGES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
               ORDER BY SEQUENCE_NUM
           END-EXEC
           EXEC SQL OPEN COV_CURSOR END-EXEC
           PERFORM 4100-FETCH-COVERAGE
               UNTIL SQLCODE NOT = 0
                  OR WS-COV-COUNT >= 20
           EXEC SQL CLOSE COV_CURSOR END-EXEC
           .

       4100-FETCH-COVERAGE.
           ADD 1 TO WS-COV-COUNT
           EXEC SQL
               FETCH COV_CURSOR
               INTO :WS-COV-TYPE(WS-COV-COUNT),
                    :WS-COV-DESC(WS-COV-COUNT),
                    :WS-COV-LIMIT(WS-COV-COUNT),
                    :WS-COV-PREMIUM(WS-COV-COUNT),
                    :WS-COV-STATUS(WS-COV-COUNT)
           END-EXEC
           IF SQLCODE NOT = 0
               SUBTRACT 1 FROM WS-COV-COUNT
           END-IF
           .

       5000-DISPLAY-POLICY.
           EXEC CICS SEND
               MAP('POLQDET')
               MAPSET('POLQMAPS')
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
               TRANSID('PQRY')
               COMMAREA(DFHCOMMAREA)
               LENGTH(WS-COMMAREA-LENGTH)
           END-EXEC
           .
