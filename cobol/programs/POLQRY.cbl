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
      * Inquiry is scoped to the signed-on user's book of business:
      * the RACF user id is resolved to a row in USER_ENTITLEMENTS and
      * the resulting agent/branch scope is applied as a predicate on
      * every policy read. Policyholder contact details are masked
      * unless the entitlement grants PII access.
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
       01  WS-NOT-FOUND-MSG          PIC X(79) VALUE
           'POLICY NOT FOUND OR NOT IN YOUR BOOK OF BUSINESS'.

       01  WS-USER-ID                PIC X(08).
       01  WS-ENTITLEMENT.
           05  WS-ENT-SCOPE          PIC X(01).
               88  ENT-SCOPE-ALL     VALUE 'A'.
               88  ENT-SCOPE-BRANCH  VALUE 'B'.
               88  ENT-SCOPE-AGENT   VALUE 'G'.
           05  WS-ENT-AGENT-CODE     PIC X(06).
           05  WS-ENT-BRANCH-CODE    PIC X(04).
           05  WS-ENT-PII-IND        PIC X(01).
               88  ENT-PII-ALLOWED   VALUE 'Y'.
       01  WS-SCOPE-IND.
           05  WS-SCOPE-ALL-IND      PIC X(01).
           05  WS-SCOPE-BRANCH-IND   PIC X(01).
           05  WS-SCOPE-AGENT-IND    PIC X(01).

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
               PERFORM 1500-READ-ENTITLEMENT
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

      ****************************************************************
      * Resolve the signed-on user to their inquiry entitlement.
      * A user with no entitlement row may not inquire on any policy.
      ****************************************************************
       1500-READ-ENTITLEMENT.
           MOVE SPACES TO WS-ENTITLEMENT
           EXEC SQL
               SELECT SCOPE_LEVEL, AGENT_CODE, BRANCH_CODE, PII_ACCESS
               INTO :WS-ENT-SCOPE, :WS-ENT-AGENT-CODE,
                    :WS-ENT-BRANCH-CODE, :WS-ENT-PII-IND
               FROM USER_ENTITLEMENTS
               WHERE USER_ID = :WS-USER-ID
                 AND ACTIVE_IND = 'Y'
           END-EXEC
           IF SQLCODE = 100
               MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                   TO WS-ERROR-MSG
           END-IF
           IF SQLCODE < 0
               MOVE 'DB2 ERROR READING ENTITLEMENT' TO WS-ERROR-MSG
           END-IF
           IF WS-ERROR-MSG = SPACES
               MOVE 'N' TO WS-SCOPE-ALL-IND
               MOVE 'N' TO WS-SCOPE-BRANCH-IND
               MOVE 'N' TO WS-SCOPE-AGENT-IND
               EVALUATE TRUE
                   WHEN ENT-SCOPE-ALL
                       MOVE 'Y' TO WS-SCOPE-ALL-IND
                   WHEN ENT-SCOPE-BRANCH
                       MOVE 'Y' TO WS-SCOPE-BRANCH-IND
                   WHEN ENT-SCOPE-AGENT
                       MOVE 'Y' TO WS-SCOPE-AGENT-IND
                   WHEN OTHER
                       MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                           TO WS-ERROR-MSG
               END-EVALUATE
           END-IF
           IF WS-ERROR-MSG = SPACES
               IF WS-SCOPE-BRANCH-IND = 'Y'
                   AND WS-ENT-BRANCH-CODE = SPACES
                   MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                       TO WS-ERROR-MSG
               END-IF
               IF WS-SCOPE-AGENT-IND = 'Y'
                   AND WS-ENT-AGENT-CODE = SPACES
                   MOVE 'NOT AUTHORIZED FOR POLICY INQUIRY'
                       TO WS-ERROR-MSG
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
               FROM POLICIES
               WHERE POLICY_NUMBER = :POLICY-NUMBER
                 AND ( :WS-SCOPE-ALL-IND = 'Y'
                    OR ( :WS-SCOPE-BRANCH-IND = 'Y'
                         AND BRANCH_CODE = :WS-ENT-BRANCH-CODE )
                    OR ( :WS-SCOPE-AGENT-IND = 'Y'
                         AND AGENT_CODE = :WS-ENT-AGENT-CODE ) )
           END-EXEC
           IF SQLCODE = 100
               MOVE WS-NOT-FOUND-MSG TO WS-ERROR-MSG
           END-IF
           IF SQLCODE < 0
               MOVE 'DB2 ERROR READING POLICY' TO WS-ERROR-MSG
           END-IF
           .

       3000-READ-CUSTOMER.
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
           IF NOT ENT-PII-ALLOWED
               MOVE ALL '*' TO CUST-PHONE
               MOVE ALL '*' TO CUST-EMAIL
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
