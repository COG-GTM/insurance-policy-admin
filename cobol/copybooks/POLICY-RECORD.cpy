      ******************************************************************
      * POLICY-RECORD.CPY - Policy Master Record Layout
      * System:   Policy Administration System (PAS)
      * Author:   J. Henderson
      * Date:     1998-03-15
      * Modified: 2005-11-20 - Added POLICY-WEB-IND for web portal
      *           2015-06-01 - Extended POLICY-NUMBER to 12 chars
      *           2022-01-15 - Added POLICY-API-FLAG for REST facade
      *           2026-10-30 - UW-REFERRED covers UNDWRT 'RS'/'RM'
      * DB2 Table: POLICIES
      ******************************************************************
       01  POLICY-RECORD.
           05  POLICY-NUMBER            PIC X(12).
           05  POLICY-TYPE              PIC X(03).
               88  POL-TYPE-AUTO        VALUE 'AUT'.
               88  POL-TYPE-HOME        VALUE 'HOM'.
               88  POL-TYPE-COMM        VALUE 'COM'.
               88  POL-TYPE-LIFE        VALUE 'LIF'.
               88  POL-TYPE-HEALTH      VALUE 'HLT'.
           05  POLICY-STATUS            PIC X(02).
               88  POL-STAT-ACTIVE      VALUE 'AC'.
               88  POL-STAT-PENDING     VALUE 'PN'.
               88  POL-STAT-CANCELLED   VALUE 'CN'.
               88  POL-STAT-EXPIRED     VALUE 'EX'.
               88  POL-STAT-LAPSED      VALUE 'LP'.
           05  POLICY-EFFECTIVE-DATE    PIC 9(08).
           05  POLICY-EXPIRY-DATE       PIC 9(08).
           05  POLICY-HOLDER-ID         PIC X(10).
           05  POLICY-AGENT-CODE        PIC X(06).
           05  POLICY-BRANCH-CODE       PIC X(04).
           05  POLICY-TOTAL-PREMIUM     PIC S9(09)V99 COMP-3.
           05  POLICY-DEDUCTIBLE        PIC S9(07)V99 COMP-3.
           05  POLICY-LIMIT             PIC S9(11)V99 COMP-3.
           05  POLICY-INCEPTION-DATE    PIC 9(08).
           05  POLICY-RENEWAL-COUNT     PIC 9(03).
           05  POLICY-UW-STATUS         PIC X(02).
               88  UW-APPROVED          VALUE 'AP'.
               88  UW-PENDING           VALUE 'PN'.
               88  UW-DECLINED          VALUE 'DC'.
               88  UW-REFERRED          VALUE 'RF' 'RS' 'RM'.
               88  UW-REFERRED-SR       VALUE 'RS'.
               88  UW-REFERRED-MGR      VALUE 'RM'.
           05  POLICY-RISK-SCORE        PIC 9(03).
           05  POLICY-WEB-IND           PIC X(01).
               88  POL-WEB-YES          VALUE 'Y'.
               88  POL-WEB-NO           VALUE 'N'.
           05  POLICY-API-FLAG          PIC X(01).
               88  POL-API-YES          VALUE 'Y'.
               88  POL-API-NO           VALUE 'N'.
           05  POLICY-LAST-UPDATED      PIC 9(08).
           05  POLICY-UPDATED-BY        PIC X(08).
           05  FILLER                   PIC X(20).
