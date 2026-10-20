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
           MOVE SPACES TO HX-ITEMS
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
                  FUNCTION TRIM(HX-TEXT TRAILING) DELIMITED BY SIZE
               INTO HX-OUT WITH POINTER HX-PTR
           .

       HX-APPEND-SCREEN.
           MOVE SPACES TO HX-WORK
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

       HX-APPEND-ITEM.
           MOVE SPACES TO HX-WORK
           IF HX-ITEMS = SPACES
               STRING FUNCTION TRIM(HX-ITEM)
                   DELIMITED BY SIZE INTO HX-WORK
           ELSE
               STRING FUNCTION TRIM(HX-ITEMS) ';'
                      FUNCTION TRIM(HX-ITEM)
                   DELIMITED BY SIZE INTO HX-WORK
           END-IF
           MOVE HX-WORK TO HX-ITEMS
           .

       HX-EMIT.
           DISPLAY FUNCTION TRIM(HX-OUT)
           .

       HX-APPEND-HEX.
           MOVE SPACES TO HX-HEX-OUT
           PERFORM VARYING HX-HEX-I FROM 1 BY 1
                   UNTIL HX-HEX-I > HX-REC-LEN
               COMPUTE HX-HEX-B = FUNCTION ORD(HX-REC(HX-HEX-I:1)) - 1
               DIVIDE HX-HEX-B BY 16 GIVING HX-HEX-HI
                   REMAINDER HX-HEX-LO
               MOVE HX-HEX-DIGITS(HX-HEX-HI + 1:1)
                   TO HX-HEX-OUT(HX-HEX-I * 2 - 1:1)
               MOVE HX-HEX-DIGITS(HX-HEX-LO + 1:1)
                   TO HX-HEX-OUT(HX-HEX-I * 2:1)
           END-PERFORM
           IF HX-REC-LEN = 0
               STRING '|' DELIMITED BY SIZE
                   INTO HX-OUT WITH POINTER HX-PTR
           ELSE
               STRING '|' DELIMITED BY SIZE
                      HX-HEX-OUT(1:HX-REC-LEN * 2) DELIMITED BY SIZE
                   INTO HX-OUT WITH POINTER HX-PTR
           END-IF
           .
