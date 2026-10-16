      ******************************************************************
      * UNDWRT stubs (AB-520). One STUB-<para>-<k> per EXEC SQL / CICS
      * block replaced by cobol_harness.py.
      ******************************************************************
       STUB-1000-1.
      *    ASKTIME - first stub executed: read fixture + init output
           PERFORM HX-READ-FIXTURE
           PERFORM HX-BEGIN
           .
       STUB-1000-2.
      *    FORMATTIME - fixture as_of_date becomes 'today'
           MOVE HX-AS-OF-DATE TO WS-CURRENT-DATE
           .
       STUB-2000-1.
      *    RECEIVE MAP UWRTMAP - map supplies the policy number
           MOVE HX-CASE-ID TO POLICY-NUMBER
           MOVE 0 TO WS-RESPONSE-CODE
           .
       STUB-2000-2.
      *    SELECT POLICIES JOIN POLICY_HOLDERS - feed row or not-found
           IF HX-SELECT-SQLCODE = 0
               MOVE HX-POLICY-TYPE      TO POLICY-TYPE
               MOVE HX-TOTAL-PREMIUM    TO POLICY-TOTAL-PREMIUM
               MOVE HX-POLICY-LIMIT     TO POLICY-LIMIT
               MOVE HX-BRANCH-CODE      TO POLICY-BRANCH-CODE
               MOVE HX-CUST-TYPE        TO CUST-TYPE
               MOVE HX-CREDIT-SCORE     TO CUST-CREDIT-SCORE
               MOVE HX-RISK-TIER        TO CUST-RISK-TIER
           END-IF
           MOVE HX-SELECT-SQLCODE TO SQLCODE
           .
       STUB-3000-1.
      *    SELECT COUNT(*), SUM(INCURRED) - 5-year claims history
           MOVE HX-CLAIM-COUNT-5Y    TO WS-CLAIM-COUNT
           MOVE HX-TOTAL-INCURRED-5Y TO WS-TOTAL-INCURRED
           MOVE 0 TO SQLCODE
           .
       STUB-4000-1.
      *    SELECT SUM(COVERAGE_LIMIT) - branch accumulation
           MOVE HX-BRANCH-ACCUM-LIMIT TO WS-ACCUM-LIMIT
           MOVE 0 TO SQLCODE
           .
       STUB-6000-1.
      *    INSERT INTO UNDERWRITING_DECISIONS - always succeeds
           MOVE 0 TO SQLCODE
           .
       STUB-6000-2.
      *    UPDATE POLICIES - always succeeds
           MOVE 0 TO SQLCODE
           .
       STUB-7000-1.
      *    WRITEQ TD (MQ notification) - no-op
           CONTINUE
           .
       STUB-8000-1.
      *    SEND MAP UWRTRES - record screen
           MOVE 'UWRTRES' TO HX-SCREEN-NAME
           PERFORM HX-APPEND-SCREEN
           .
       STUB-9000-1.
      *    SEND TEXT - record screen
           MOVE 'TEXT' TO HX-SCREEN-NAME
           PERFORM HX-APPEND-SCREEN
           .
       STUB-9999-1.
      *    RETURN - emit outputs then end the 'task'
           MOVE WS-ERROR-MSG TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           MOVE HX-SCREENS TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           MOVE WS-RISK-SCORE TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE WS-DECISION TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           MOVE WS-DECISION-REASON TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           MOVE WS-LOSS-RATIO TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE POLICY-RISK-SCORE TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE POLICY-UW-STATUS TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           PERFORM HX-EMIT
           STOP RUN
           .
