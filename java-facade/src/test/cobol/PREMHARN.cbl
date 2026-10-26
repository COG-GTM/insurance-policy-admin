       IDENTIFICATION DIVISION.
       PROGRAM-ID. PREMHARN.
      * Parity harness: 3200-CALCULATE-PREMIUM from PREMBAT.cbl with the
      * hard-coded 1.00 factors replaced by inputs of the WS factor PIC.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CASES ASSIGN TO KEYBOARD
               ORGANIZATION IS LINE SEQUENTIAL.
       DATA DIVISION.
       FILE SECTION.
       FD  CASES.
       01  CASE-REC.
           05  IN-TYPE               PIC X(03).
           05  FILLER                PIC X(01).
           05  IN-TERR               PIC S9(03)V9999
                                     SIGN LEADING SEPARATE.
           05  FILLER                PIC X(01).
           05  IN-CLASS              PIC S9(03)V9999
                                     SIGN LEADING SEPARATE.
           05  FILLER                PIC X(01).
           05  IN-EXP                PIC S9(03)V9999
                                     SIGN LEADING SEPARATE.
       WORKING-STORAGE SECTION.
       01  WS-EOF                    PIC X VALUE 'N'.
       01  POLICY-TYPE               PIC X(03).
           88  POL-TYPE-AUTO        VALUE 'AUT'.
           88  POL-TYPE-HOME        VALUE 'HOM'.
           88  POL-TYPE-COMM        VALUE 'COM'.
           88  POL-TYPE-LIFE        VALUE 'LIF'.
           88  POL-TYPE-HEALTH      VALUE 'HLT'.
       01  WS-TERR-FACTOR            PIC S9(03)V9999 COMP-3.
       01  WS-CLS-FACTOR             PIC S9(03)V9999 COMP-3.
       01  WS-EXP-MOD                PIC S9(03)V9999 COMP-3.
       01  WS-CALC-FIELDS.
           05  WS-BASE-PREMIUM       PIC S9(09)V99 COMP-3.
           05  WS-TERR-PREMIUM       PIC S9(09)V99 COMP-3.
           05  WS-CLASS-PREMIUM      PIC S9(09)V99 COMP-3.
           05  WS-MOD-PREMIUM        PIC S9(09)V99 COMP-3.
           05  WS-TAX-AMOUNT         PIC S9(07)V99 COMP-3.
           05  WS-SURCHARGE          PIC S9(07)V99 COMP-3.
           05  WS-FINAL-PREMIUM      PIC S9(09)V99 COMP-3.
       01  WS-TAX-RATE               PIC S9(01)V9999 COMP-3
           VALUE 0.0350.
       01  O9                        PIC -9(09).99.
       01  O7                        PIC -9(07).99.
       01  OFAC                        PIC -9(03).9999.
       PROCEDURE DIVISION.
           OPEN INPUT CASES
           PERFORM UNTIL WS-EOF = 'Y'
               READ CASES AT END MOVE 'Y' TO WS-EOF
               NOT AT END PERFORM RATE-ONE
               END-READ
           END-PERFORM
           CLOSE CASES
           STOP RUN.
       RATE-ONE.
           MOVE IN-TYPE  TO POLICY-TYPE
           MOVE IN-TERR  TO WS-TERR-FACTOR
           MOVE IN-CLASS TO WS-CLS-FACTOR
           MOVE IN-EXP   TO WS-EXP-MOD
           EVALUATE TRUE
               WHEN POL-TYPE-AUTO
                   MOVE 850.00 TO WS-BASE-PREMIUM
               WHEN POL-TYPE-HOME
                   MOVE 1200.00 TO WS-BASE-PREMIUM
               WHEN POL-TYPE-COMM
                   MOVE 5000.00 TO WS-BASE-PREMIUM
               WHEN POL-TYPE-LIFE
                   MOVE 400.00 TO WS-BASE-PREMIUM
               WHEN POL-TYPE-HEALTH
                   MOVE 3500.00 TO WS-BASE-PREMIUM
               WHEN OTHER
                   MOVE 1000.00 TO WS-BASE-PREMIUM
           END-EVALUATE
           COMPUTE WS-TERR-PREMIUM =
               WS-BASE-PREMIUM * WS-TERR-FACTOR
           COMPUTE WS-CLASS-PREMIUM =
               WS-TERR-PREMIUM * WS-CLS-FACTOR
           COMPUTE WS-MOD-PREMIUM =
               WS-CLASS-PREMIUM * WS-EXP-MOD
           COMPUTE WS-TAX-AMOUNT =
               WS-MOD-PREMIUM * WS-TAX-RATE
           MOVE 25.00 TO WS-SURCHARGE
           COMPUTE WS-FINAL-PREMIUM =
               WS-MOD-PREMIUM + WS-TAX-AMOUNT + WS-SURCHARGE
           DISPLAY '"' IN-TYPE '",' WITH NO ADVANCING
           MOVE WS-TERR-FACTOR TO OFAC
           DISPLAY OFAC ',' WITH NO ADVANCING
           MOVE WS-CLS-FACTOR TO OFAC
           DISPLAY OFAC ',' WITH NO ADVANCING
           MOVE WS-EXP-MOD TO OFAC
           DISPLAY OFAC ',' WITH NO ADVANCING
           MOVE WS-BASE-PREMIUM TO O9
           DISPLAY O9 ',' WITH NO ADVANCING
           MOVE WS-TERR-PREMIUM TO O9
           DISPLAY O9 ',' WITH NO ADVANCING
           MOVE WS-CLASS-PREMIUM TO O9
           DISPLAY O9 ',' WITH NO ADVANCING
           MOVE WS-MOD-PREMIUM TO O9
           DISPLAY O9 ',' WITH NO ADVANCING
           MOVE WS-TAX-AMOUNT TO O7
           DISPLAY O7 ',' WITH NO ADVANCING
           MOVE WS-SURCHARGE TO O7
           DISPLAY O7 ',' WITH NO ADVANCING
           MOVE WS-FINAL-PREMIUM TO O9 DISPLAY O9.
