      ******************************************************************
      * POLEND stubs (AB-520). One STUB-<para>-<k> per EXEC SQL / CICS
      * block replaced by cobol_harness.py.
      ******************************************************************
       STUB-1000-1.
      *    ASKTIME - first stub executed: read fixture + init output.
      *    ws_endorsement_type simulates the initial content of
      *    WS-ENDORSEMENT-TYPE (never populated by the program).
           PERFORM HX-READ-FIXTURE
           PERFORM HX-BEGIN
           MOVE HX-WS-ENDORSEMENT-TYPE TO WS-ENDORSEMENT-TYPE
           .
       STUB-1000-2.
      *    FORMATTIME - fixture as_of_date becomes 'today'
           MOVE HX-AS-OF-DATE TO WS-CURRENT-DATE
           .
       STUB-2000-1.
      *    RECEIVE MAP POLEMAP - map supplies the policy number
           MOVE HX-CASE-ID TO POLICY-NUMBER
           MOVE 0 TO WS-RESPONSE-CODE
           .
       STUB-2000-2.
      *    SELECT FROM POLICIES (WITH RS) - feed row or not-found
           IF HX-SELECT-SQLCODE = 0
               MOVE HX-POLICY-STATUS    TO POLICY-STATUS
               MOVE HX-EFFECTIVE-DATE   TO POLICY-EFFECTIVE-DATE
               MOVE HX-EXPIRY-DATE      TO POLICY-EXPIRY-DATE
               MOVE HX-TOTAL-PREMIUM    TO POLICY-TOTAL-PREMIUM
           END-IF
           MOVE HX-SELECT-SQLCODE TO SQLCODE
           .
       STUB-5000-1.
      *    SELECT MAX(ENDORSEMENT_SEQ)+1 - next seq from fixture
           COMPUTE WS-ENDORSEMENT-SEQ = HX-MAX-ENDORSEMENT-SEQ + 1
           MOVE 0 TO SQLCODE
           .
       STUB-5000-2.
      *    INSERT INTO ENDORSEMENTS - sqlcode from fixture
           MOVE HX-INSERT-SQLCODE TO SQLCODE
           .
       STUB-5000-3.
      *    UPDATE POLICIES - always succeeds
           MOVE 0 TO SQLCODE
           .
       STUB-6000-1.
      *    WRITEQ TS ENDORSEMENT-AUDIT - no-op
           CONTINUE
           .
       STUB-7000-1.
      *    SEND MAP POLECONF - record screen
           MOVE 'POLECONF' TO HX-SCREEN-NAME
           PERFORM HX-APPEND-SCREEN
           .
       STUB-8000-1.
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
           MOVE WS-DAYS-IN-TERM TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE WS-DAYS-REMAINING TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE WS-PRORATA-FACTOR TO HX-FACTOR6
           PERFORM HX-APPEND-R6
           MOVE WS-PREMIUM-ADJUST TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE WS-ENDORSEMENT-SEQ TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE POLICY-TOTAL-PREMIUM TO HX-AMT
           PERFORM HX-APPEND-AMT
           PERFORM HX-EMIT
           STOP RUN
           .
