      ******************************************************************
      * POLNEW message-capture stubs (AB-528, from AB-525). One STUB-<para>-<k> per EXEC SQL / CICS
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
           MOVE 0 TO WS-CURRENT-TIME
           .
       STUB-2000-1.
      *    RECEIVE MAP POLNMAP - map fills POLICY-RECORD
           MOVE SPACES TO POLICY-RECORD
           MOVE HX-POLICY-TYPE      TO POLICY-TYPE
           MOVE HX-EFFECTIVE-DATE   TO POLICY-EFFECTIVE-DATE
           MOVE HX-EXPIRY-DATE      TO POLICY-EXPIRY-DATE
           MOVE HX-HOLDER-ID        TO POLICY-HOLDER-ID
           MOVE HX-AGENT-CODE       TO POLICY-AGENT-CODE
           MOVE HX-BRANCH-CODE      TO POLICY-BRANCH-CODE
           MOVE HX-TOTAL-PREMIUM    TO POLICY-TOTAL-PREMIUM
           MOVE HX-DEDUCTIBLE       TO POLICY-DEDUCTIBLE
           MOVE HX-POLICY-LIMIT     TO POLICY-LIMIT
           MOVE 0 TO WS-RESPONSE-CODE
           .
       STUB-3000-1.
      *    SELECT CUST_ID FROM POLICY_HOLDERS - found or not found
           IF HX-HOLDER-SQLCODE = 0
               MOVE POLICY-HOLDER-ID TO CUST-ID
           END-IF
           MOVE HX-HOLDER-SQLCODE TO SQLCODE
           .
       STUB-4000-1.
      *    SELECT NEXT VALUE FOR POLICY_SEQ - value from fixture
           MOVE HX-POLICY-SEQ TO WS-POLICY-SEQ
           MOVE 0 TO SQLCODE
           .
       STUB-5000-1.
      *    INSERT INTO POLICIES - sqlcode from fixture
           MOVE HX-INSERT-SQLCODE TO SQLCODE
           IF HX-INSERT-SQLCODE = 0
               MOVE 'POLICIES' TO HX-ITEM
           ELSE
               MOVE 'POLICIES-FAILED' TO HX-ITEM
           END-IF
           PERFORM HX-APPEND-ITEM
           .
       STUB-6100-1.
      *    INSERT INTO COVERAGES - always succeeds; record seq + type
           MOVE SPACES TO HX-ITEM
           STRING 'COV' COV-SEQUENCE-NUM ':' COV-TYPE-CODE
               DELIMITED BY SIZE INTO HX-ITEM
           PERFORM HX-APPEND-ITEM
           MOVE 0 TO SQLCODE
           .
       STUB-7000-1.
      *    WRITEQ TD underwriting request - record the write
           MOVE POLICY-RECORD TO HX-REC
           MOVE LENGTH OF POLICY-RECORD TO HX-REC-LEN
           MOVE 'TDQ' TO HX-ITEM
           PERFORM HX-APPEND-ITEM
           MOVE 0 TO WS-RESPONSE-CODE
           .
       STUB-8000-1.
      *    SEND MAP POLNCONF - record screen
           MOVE 'POLNCONF' TO HX-SCREEN-NAME
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
           PERFORM HX-APPEND-HEX
           PERFORM HX-EMIT
           STOP RUN
           .
