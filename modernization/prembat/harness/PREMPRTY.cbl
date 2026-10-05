       IDENTIFICATION DIVISION.
       PROGRAM-ID. PREMPRTY.
      ******************************************************************
      * PREMPRTY - Parity harness for PREMBAT 3200-CALCULATE-PREMIUM
      *
      * Runs fixture records through the premium calculation logic of
      * cobol/programs/PREMBAT.cbl under GnuCOBOL. The DB2 cursor and
      * PREMIUMS insert are replaced by a fixture file and a
      * pipe-delimited output file; the calculation itself is NOT
      * re-implemented here. run-parity.sh extracts it from PREMBAT.cbl
      * at build time into two generated copybooks:
      *
      *   PREMBAT-WS.cpy         WS-CALC-FIELDS + WS-TAX-RATE (verbatim)
      *   PREMBAT-CALC.cpy       3200-CALCULATE-PREMIUM (verbatim)
      *   PREMBAT-CALC-FACT.cpy  same paragraph with the three
      *                          hardcoded 1.00 factors parameterised
      *
      * Input record (37 bytes, LINE SEQUENTIAL):
      *   MODE(1) L = legacy (verbatim paragraph)
      *           F = factor sweep (parameterised paragraph)
      *   CASE-ID(12) POLICY-TYPE(3)
      *   TERR/CLASS/EXP-MOD factors, each 9(3)V9(4) display
      * Lines starting with '*' are comments.
      ******************************************************************
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT PARITY-IN  ASSIGN TO 'PARITYIN'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-IN-STATUS.
           SELECT PARITY-OUT ASSIGN TO 'PARITYOUT'
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS WS-OUT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  PARITY-IN.
       01  PARITY-IN-REC.
           05  IN-MODE                PIC X(01).
           05  IN-CASE-ID             PIC X(12).
           05  IN-POLICY-TYPE         PIC X(03).
           05  IN-TERR-FACTOR         PIC 9(03)V9(04).
           05  IN-CLASS-FACTOR        PIC 9(03)V9(04).
           05  IN-EXP-MOD             PIC 9(03)V9(04).
       FD  PARITY-OUT.
       01  PARITY-OUT-REC             PIC X(160).

       WORKING-STORAGE SECTION.
       01  WS-IN-STATUS              PIC X(02).
       01  WS-OUT-STATUS             PIC X(02).
       01  WS-EOF                    PIC X(01) VALUE 'N'.
           88  END-OF-INPUT          VALUE 'Y'.
       01  WS-CASES                  PIC 9(07) VALUE 0.

       COPY POLICY-RECORD.
       COPY PREMBAT-WS.

       01  WS-PARAM-FACTORS.
           05  WS-P-TERR-FACTOR      PIC S9(03)V9999 COMP-3.
           05  WS-P-CLASS-FACTOR     PIC S9(03)V9999 COMP-3.
           05  WS-P-EXP-MOD          PIC S9(03)V9999 COMP-3.

       01  WS-EDITED.
           05  WS-ED-BASE            PIC -(10)9.99.
           05  WS-ED-TERR            PIC -(10)9.99.
           05  WS-ED-CLASS           PIC -(10)9.99.
           05  WS-ED-MOD             PIC -(10)9.99.
           05  WS-ED-TAX             PIC -(10)9.99.
           05  WS-ED-SURCH           PIC -(10)9.99.
           05  WS-ED-FINAL           PIC -(10)9.99.

       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT PARITY-IN
           IF WS-IN-STATUS NOT = '00'
               DISPLAY 'PREMPRTY: CANNOT OPEN PARITYIN ' WS-IN-STATUS
               MOVE 16 TO RETURN-CODE
               STOP RUN
           END-IF
           OPEN OUTPUT PARITY-OUT
           PERFORM UNTIL END-OF-INPUT
               READ PARITY-IN
                   AT END SET END-OF-INPUT TO TRUE
                   NOT AT END PERFORM 1000-PROCESS-RECORD
               END-READ
           END-PERFORM
           CLOSE PARITY-IN PARITY-OUT
           DISPLAY 'PREMPRTY: CASES PROCESSED ' WS-CASES
           STOP RUN.

       1000-PROCESS-RECORD.
           IF IN-MODE = '*' OR IN-MODE = SPACE
               EXIT PARAGRAPH
           END-IF
           MOVE IN-POLICY-TYPE TO POLICY-TYPE
           EVALUATE IN-MODE
               WHEN 'L'
                   PERFORM 3200-CALCULATE-PREMIUM
               WHEN 'F'
                   MOVE IN-TERR-FACTOR  TO WS-P-TERR-FACTOR
                   MOVE IN-CLASS-FACTOR TO WS-P-CLASS-FACTOR
                   MOVE IN-EXP-MOD      TO WS-P-EXP-MOD
                   PERFORM 3200-CALCULATE-PREMIUM-FACT
               WHEN OTHER
                   DISPLAY 'PREMPRTY: BAD MODE ' IN-MODE
                   MOVE 8 TO RETURN-CODE
                   EXIT PARAGRAPH
           END-EVALUATE
           ADD 1 TO WS-CASES
           PERFORM 2000-WRITE-RESULT
           .

       2000-WRITE-RESULT.
           MOVE WS-BASE-PREMIUM  TO WS-ED-BASE
           MOVE WS-TERR-PREMIUM  TO WS-ED-TERR
           MOVE WS-CLASS-PREMIUM TO WS-ED-CLASS
           MOVE WS-MOD-PREMIUM   TO WS-ED-MOD
           MOVE WS-TAX-AMOUNT    TO WS-ED-TAX
           MOVE WS-SURCHARGE     TO WS-ED-SURCH
           MOVE WS-FINAL-PREMIUM TO WS-ED-FINAL
           MOVE SPACES TO PARITY-OUT-REC
           STRING IN-MODE                     DELIMITED BY SIZE
                  '|' FUNCTION TRIM(IN-CASE-ID) DELIMITED BY SIZE
                  '|[' IN-POLICY-TYPE ']'     DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-BASE)  DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-TERR)  DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-CLASS) DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-MOD)   DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-TAX)   DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-SURCH) DELIMITED BY SIZE
                  '|' FUNCTION TRIM(WS-ED-FINAL) DELIMITED BY SIZE
               INTO PARITY-OUT-REC
           END-STRING
           WRITE PARITY-OUT-REC
           .

       COPY PREMBAT-CALC.

       COPY PREMBAT-CALC-FACT.
