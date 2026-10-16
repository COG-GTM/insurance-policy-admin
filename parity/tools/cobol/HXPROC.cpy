      ******************************************************************
      * HXPROC.CPY - harness paragraphs (AB-520)
      * Appended after the program body and HXSTUBS by cobol_harness.py.
      *
      * Output formats (must match com.acme...parity.ParityFormat):
      *   money   -(13)9.99  then TRIM -> "1267.00", "0.00", "-0.05"
      *   4dp     -(5)9.9(4) then TRIM -> "850.0000"
      *   6dp     -(3)9.9(6) then TRIM -> "0.500000"
      *   integer -(12)9     then TRIM -> "1"
      *   text    trailing spaces trimmed (all-blank -> empty field)
      ******************************************************************
       HX-READ-FIXTURE.
           ACCEPT HX-FIXTURE
           .

       HX-BEGIN.
           MOVE SPACES TO HX-OUT
           MOVE SPACES TO HX-SCREENS
           MOVE 1 TO HX-PTR
           STRING 'PARITY|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-CASE-ID) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-AMT.
           MOVE HX-AMT TO HX-ED-AMT
           STRING '|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-ED-AMT) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-R4.
           MOVE HX-RATE4 TO HX-ED-R4
           STRING '|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-ED-R4) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-R6.
           MOVE HX-FACTOR6 TO HX-ED-R6
           STRING '|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-ED-R6) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-INT.
           MOVE HX-INT TO HX-ED-INT
           STRING '|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-ED-INT) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-TEXT.
           STRING '|' DELIMITED BY SIZE
                  FUNCTION TRIM(HX-TEXT) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-SCREEN.
           IF HX-SCREENS = SPACES
               STRING FUNCTION TRIM(HX-SCREEN-NAME)
                   DELIMITED BY SIZE INTO HX-WORK
           ELSE
               STRING FUNCTION TRIM(HX-SCREENS) '+'
                      FUNCTION TRIM(HX-SCREEN-NAME)
                   DELIMITED BY SIZE INTO HX-WORK
           END-IF
           MOVE HX-WORK TO HX-SCREENS
           .

       HX-EMIT.
           DISPLAY FUNCTION TRIM(HX-OUT)
           .
