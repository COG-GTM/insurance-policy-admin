      ******************************************************************
      * PXSQLCA - SQL communication area for the parity harness (AB-520)
      * Replaces EXEC SQL INCLUDE SQLCA. Only SQLCODE is used by PAS.
      ******************************************************************
       01  SQLCA.
           05  SQLCAID               PIC X(08) VALUE 'SQLCA'.
           05  SQLCABC               PIC S9(09) COMP-5 VALUE 136.
           05  SQLCODE               PIC S9(09) COMP-5 VALUE 0.
           05  SQLERRM.
               49  SQLERRML          PIC S9(04) COMP-5 VALUE 0.
               49  SQLERRMC          PIC X(70) VALUE SPACES.
           05  SQLERRP               PIC X(08) VALUE SPACES.
           05  SQLERRD               PIC S9(09) COMP-5 OCCURS 6.
           05  SQLWARN               PIC X(11) VALUE SPACES.
           05  SQLSTATE              PIC X(05) VALUE '00000'.
