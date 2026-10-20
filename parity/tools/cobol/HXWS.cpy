      ******************************************************************
      * HXWS.CPY - harness working storage (AB-520)
      * Appended at the end of WORKING-STORAGE by cobol_harness.py.
      * HX-OUT accumulates one PARITY| output line; HX-SCREENS records
      * the CICS maps/TEXT sent, joined with '+'. HX-ITEMS (AB-525)
      * records ordered side effects, joined with ';'.
      ******************************************************************
       01  HX-OUT                  PIC X(2000).
       01  HX-PTR                  PIC 9(05) COMP-5 VALUE 1.
       01  HX-SCREENS              PIC X(400).
      * HX-ITEMS records side effects (DB writes, TDQ) joined with ';'
       01  HX-ITEMS                PIC X(400).
       01  HX-ITEM                 PIC X(80).
      * append-helper inputs
       01  HX-AMT                  PIC S9(13)V99.
       01  HX-RATE4                PIC S9(05)V9(04).
       01  HX-FACTOR6              PIC S9(03)V9(06).
       01  HX-INT                  PIC S9(12).
       01  HX-TEXT                 PIC X(200).
       01  HX-SCREEN-NAME          PIC X(40).
       01  HX-WORK                 PIC X(400).
      * edited temporaries for the append helpers
       01  HX-ED-AMT               PIC -(13)9.99.
       01  HX-ED-R4                PIC -(5)9.9(4).
       01  HX-ED-R6                PIC -(3)9.9(6).
       01  HX-ED-INT               PIC -(12)9.
      * AB-528: HX-APPEND-HEX dumps HX-REC(1:HX-REC-LEN) as hex so the
      * queue message bytes (e.g. POLICY-RECORD) can be golden-tested.
       01  HX-REC                  PIC X(256).
       01  HX-REC-LEN              PIC 9(04) COMP-5 VALUE 0.
       01  HX-HEX-I                PIC 9(04) COMP-5.
       01  HX-HEX-B                PIC 9(04) COMP-5.
       01  HX-HEX-HI               PIC 9(04) COMP-5.
       01  HX-HEX-LO               PIC 9(04) COMP-5.
       01  HX-HEX-DIGITS           PIC X(16) VALUE '0123456789ABCDEF'.
       01  HX-HEX-OUT              PIC X(512).
