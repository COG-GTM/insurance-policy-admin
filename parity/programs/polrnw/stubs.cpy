      ******************************************************************
      * POLRNW stubs (AB-520). One STUB-<para>-<k> per EXEC SQL / CICS
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
      *    RECEIVE MAP POLRMAP - map supplies the policy number
           MOVE HX-CASE-ID TO POLICY-NUMBER
           MOVE 0 TO WS-RESPONSE-CODE
           .
       STUB-2000-2.
      *    SELECT FROM POLICIES - feed row or not-found
           IF HX-SELECT-SQLCODE = 0
               MOVE HX-POLICY-TYPE      TO POLICY-TYPE
               MOVE HX-POLICY-STATUS    TO POLICY-STATUS
               MOVE HX-TOTAL-PREMIUM    TO POLICY-TOTAL-PREMIUM
               MOVE HX-EXPIRY-DATE      TO POLICY-EXPIRY-DATE
               MOVE HX-RENEWAL-COUNT    TO POLICY-RENEWAL-COUNT
               MOVE HX-UW-STATUS        TO POLICY-UW-STATUS
           END-IF
           MOVE HX-SELECT-SQLCODE TO SQLCODE
           .
       STUB-3000-1.
      *    SELECT COUNT(*) INTO :WS-SQLCODE - claims count lands in
      *    WS-SQLCODE, overwriting the field (quirk: not enforced)
           MOVE HX-OPEN-CLAIMS TO WS-SQLCODE
           MOVE 0 TO SQLCODE
           .
       STUB-6000-1.
      *    UPDATE POLICIES - sqlcode from fixture
           MOVE HX-UPDATE-SQLCODE TO SQLCODE
           .
       STUB-7000-1.
      *    UPDATE COVERAGES - always succeeds
           MOVE 0 TO SQLCODE
           .
       STUB-8000-1.
      *    SEND MAP POLRCONF - record screen
           MOVE 'POLRCONF' TO HX-SCREEN-NAME
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
           MOVE WS-NEW-PREMIUM TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE WS-RATE-CHANGE-PCT TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE POLICY-TOTAL-PREMIUM TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE POLICY-EFFECTIVE-DATE TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE POLICY-EXPIRY-DATE TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE POLICY-RENEWAL-COUNT TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE POLICY-STATUS TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           MOVE POLICY-UW-STATUS TO HX-TEXT
           PERFORM HX-APPEND-TEXT
           PERFORM HX-EMIT
           STOP RUN
           .
