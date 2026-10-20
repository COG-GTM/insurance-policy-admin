      ******************************************************************
      * PREMBAT rerun stubs (AB-523). One STUB-<para>-<k> per EXEC SQL block
      * replaced by cobol_harness.py; see build/prembat/stubs.txt for the
      * original EXEC text each stub stands in for.
      ******************************************************************
       STUB-2000-1.
      *    DECLARE TERR_CURSOR - no-op
           CONTINUE
           .
       STUB-2000-2.
      *    OPEN TERR_CURSOR
           MOVE 0 TO SQLCODE
           .
       STUB-2000-3.
      *    CLOSE TERR_CURSOR
           MOVE 0 TO SQLCODE
           .
       STUB-3000-1.
      *    DECLARE POL_CURSOR - no-op
           CONTINUE
           .
       STUB-3000-2.
      *    OPEN POL_CURSOR
           MOVE 0 TO SQLCODE
           .
       STUB-3000-3.
      *    CLOSE POL_CURSOR
           MOVE 0 TO SQLCODE
           .
       STUB-3100-1.
      *    FETCH POL_CURSOR - next fixture row; '*EOF*' -> SQLCODE 100
           PERFORM HX-READ-FIXTURE
           IF HX-FIXTURE(1:5) = '*EOF*'
               MOVE 100 TO SQLCODE
           ELSE
               MOVE HX-CASE-ID          TO POLICY-NUMBER
               MOVE HX-POLICY-TYPE      TO POLICY-TYPE
               MOVE HX-TOTAL-PREMIUM    TO POLICY-TOTAL-PREMIUM
               MOVE HX-DEDUCTIBLE       TO POLICY-DEDUCTIBLE
               MOVE HX-COVERAGE-LIMIT   TO POLICY-LIMIT
               MOVE HX-EFFECTIVE-DATE   TO POLICY-EFFECTIVE-DATE
               MOVE HX-EXPIRY-DATE      TO POLICY-EXPIRY-DATE
               MOVE 0 TO SQLCODE
           END-IF
           .
       STUB-3300-1.
      *    INSERT INTO PREMIUMS - SQLCODE from the fixture (-803 = the
      *    first run's row exists); emit what the program tried to write
           MOVE HX-PREMIUMS-SQLCODE  TO SQLCODE
           PERFORM HX-BEGIN
           MOVE PREM-TERM-EFF-DATE   TO HX-INT
           PERFORM HX-APPEND-INT
           MOVE PREM-TOTAL-PREMIUM   TO HX-AMT
           PERFORM HX-APPEND-AMT
           MOVE SQLCODE              TO HX-INT
           PERFORM HX-APPEND-INT
           PERFORM HX-EMIT
           .
