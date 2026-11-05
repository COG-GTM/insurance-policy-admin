      ******************************************************************
      * PXPROC - parity harness paragraphs (AB-520)
      * Appended after the last paragraph of the precompiled program.
      * Every stubbed EXEC CICS RETURN / STOP RUN ends the run first,
      * so control never falls through into these paragraphs.
      ******************************************************************
       PX-HARNESS-END.
           STOP RUN.

      * Read fixture value PX_<PX-NAME> (PX_R<row>_<PX-NAME> in row
      * scope) into PX-VALUE. PX-FOUND tells whether it was set.
       PX-GET.
           MOVE SPACES TO PX-ENV-NAME PX-VALUE
           IF PX-ROW-SCOPE
               MOVE PX-ROW TO PX-ROW-TXT
               STRING 'PX_R' PX-ROW-TXT '_'
                      FUNCTION TRIM(PX-NAME)
                   DELIMITED BY SIZE INTO PX-ENV-NAME
           ELSE
               STRING 'PX_' FUNCTION TRIM(PX-NAME)
                   DELIMITED BY SIZE INTO PX-ENV-NAME
           END-IF
           DISPLAY PX-ENV-NAME UPON ENVIRONMENT-NAME
           ACCEPT PX-VALUE FROM ENVIRONMENT-VALUE
               ON EXCEPTION
                   MOVE 'N' TO PX-FOUND
                   MOVE SPACES TO PX-VALUE
               NOT ON EXCEPTION
                   MOVE 'Y' TO PX-FOUND
           END-ACCEPT
           .

      * Read a numeric fixture value into PX-NUM (0 when not set).
       PX-GET-NUM.
           PERFORM PX-GET
           IF PX-IS-FOUND AND PX-VALUE NOT = SPACES
               COMPUTE PX-NUM = FUNCTION NUMVAL(PX-VALUE)
           ELSE
               MOVE 0 TO PX-NUM
           END-IF
           .

      * Emulated SQLCODE of the next statement: 0, or the value of
      * fixture SQLCODE_<PX-NAME> (fault injection).
       PX-SQLCODE.
           MOVE SPACES TO PX-ENV-NAME
           STRING 'SQLCODE_' FUNCTION TRIM(PX-NAME)
               DELIMITED BY SIZE INTO PX-ENV-NAME
           MOVE PX-ENV-NAME TO PX-NAME
           PERFORM PX-GET-NUM
           MOVE PX-NUM TO SQLCODE
           .

      * DB2 rejects an invalid DATE value with SQLCODE -181.
       PX-CHECK-DATE.
           IF FUNCTION TEST-DATE-YYYYMMDD(PX-DATE-CHECK) NOT = 0
               MOVE -181 TO SQLCODE
           END-IF
           .

      * Start of an observed effect (SQL write, CICS send, queue write).
       PX-EFFECT.
           DISPLAY 'PX|E|' FUNCTION TRIM(PX-NAME)
           .

       PX-EFFECT-SQLCODE.
           MOVE SQLCODE TO PX-NUM-OUT
           DISPLAY 'PX|C|' PX-NUM-OUT
           .

      * Text field of the current effect: PX-VALUE(1:PX-LEN).
       PX-PUT-TEXT.
           DISPLAY 'PX|T|' FUNCTION TRIM(PX-NAME) '|'
               PX-VALUE(1:PX-LEN)
           .

      * Numeric field of the current effect: PX-NUM with PX-SCALE.
       PX-PUT-NUM.
           MOVE PX-NUM TO PX-NUM-OUT
           DISPLAY 'PX|N|' FUNCTION TRIM(PX-NAME) '|' PX-SCALE '|'
               PX-NUM-OUT
           .

      * Internal working-storage value (probe), not a host variable.
       PX-PUT-PROBE.
           MOVE PX-NUM TO PX-NUM-OUT
           DISPLAY 'PX|P|' FUNCTION TRIM(PX-NAME) '|' PX-SCALE '|'
               PX-NUM-OUT
           .

      * z/OS decimal-divide exception (S0CB). Under CICS the task
      * abends ASRA and the unit of work is backed out.
       PX-ABEND-S0CB.
           DISPLAY 'PX|A|S0CB'
           MOVE 12 TO RETURN-CODE
           STOP RUN
           .
