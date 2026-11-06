      ******************************************************************
      * PXWORK - parity harness working storage (AB-520)
      * Fields used by the EXEC SQL / EXEC CICS stubs to read fixture
      * values (environment PX_<NAME>) and to print observed effects
      * (stdout lines starting with PX|). Never part of production.
      ******************************************************************
       01  PX-NAME                   PIC X(40).
       01  PX-VALUE                  PIC X(200).
       01  PX-LEN                    PIC 9(04) COMP-5 VALUE 0.
       01  PX-ENV-NAME               PIC X(60).
       01  PX-FOUND                  PIC X(01) VALUE 'N'.
           88  PX-IS-FOUND           VALUE 'Y'.
       01  PX-NUM                    PIC S9(13)V9(06) COMP-3.
       01  PX-SCALE                  PIC 9(01) VALUE 0.
       01  PX-NUM-OUT                PIC -9(13).9(06).
       01  PX-USE-ROW                PIC X(01) VALUE 'N'.
           88  PX-ROW-SCOPE          VALUE 'Y'.
       01  PX-ROW                    PIC 9(04) VALUE 0.
       01  PX-ROWS                   PIC 9(04) VALUE 0.
       01  PX-ROW-TXT                PIC 9(04).
       01  PX-DATE-CHECK             PIC 9(08).
