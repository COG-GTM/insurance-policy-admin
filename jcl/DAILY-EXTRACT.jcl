//POLEXT   JOB (ACME,PAS),'DAILY POLICY EXTRACT',
//         CLASS=A,MSGCLASS=X,MSGLEVEL=(1,1),
//         NOTIFY=&SYSUID,TIME=(,30)
//*******************************************************************
//* DAILY-EXTRACT.JCL - Nightly Policy Data Extract to Claims
//* System:   Policy Administration System (PAS)
//* Schedule: Daily at 23:00 EST (CA-7 Job# PAS0100)
//* Author:   J. Henderson
//* Date:     1998-09-01
//* Modified: 2005-03-15 - Added Claims system extract
//*           2012-11-01 - (Entry corrected: the "encryption step"
//*                        logged here was never implemented)
//*           2026-10-16 - STEP030 requires FTPS (TLS); login moved
//*                        from the INPUT stream to a NETRC dataset
//*
//* Extracts active policy data from DB2 and creates flat files
//* for consumption by Claims Engine (VB6 batch import) and
//* Broker Portal (linked server refresh).
//*
//* OUTPUT FILES:
//*   ACME.PAS.EXTRACT.POLICY.DAILY    - Policy header extract
//*   ACME.PAS.EXTRACT.COVERAGE.DAILY  - Coverage detail extract
//*   ACME.PAS.EXTRACT.CUSTOMER.DAILY  - Customer data extract
//*
//* DEPENDENCIES: DB2 subsystem DBPD must be active
//*******************************************************************
//*
//JOBLIB   DD DSN=ACME.PAS.LOADLIB,DISP=SHR
//         DD DSN=DB2.V12.SDSNLOAD,DISP=SHR
//*
//*-------------------------------------------------------------------
//* STEP010 - Extract policy headers from DB2
//*-------------------------------------------------------------------
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD DSN=DB2.V12.SDSNLOAD,DISP=SHR
//SYSTSPRT DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//SYSUDUMP DD SYSOUT=*
//POLOUT   DD DSN=ACME.PAS.EXTRACT.POLICY.DAILY,
//         DISP=(NEW,CATLG,DELETE),
//         UNIT=SYSDA,SPACE=(CYL,(50,10),RLSE),
//         DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//SYSTSIN  DD *
  DSN SYSTEM(DBPD)
  RUN PROGRAM(DSNTEP2) PLAN(DSNTEP12) -
      LIB('ACME.PAS.LOADLIB')
  END
/*
//SYSIN    DD *
  SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS,
         EFFECTIVE_DATE, EXPIRY_DATE,
         POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE,
         TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT,
         UW_STATUS, RISK_SCORE
  FROM POLICIES
  WHERE POLICY_STATUS IN ('AC', 'PN')
    AND LAST_UPDATED >= CURRENT DATE - 1 DAY
  ORDER BY POLICY_NUMBER;
/*
//*
//*-------------------------------------------------------------------
//* STEP020 - Extract coverage details
//*-------------------------------------------------------------------
//STEP020  EXEC PGM=IKJEFT01,DYNAMNBR=20,
//         COND=(4,LT,STEP010)
//STEPLIB  DD DSN=DB2.V12.SDSNLOAD,DISP=SHR
//SYSTSPRT DD SYSOUT=*
//COVOUT   DD DSN=ACME.PAS.EXTRACT.COVERAGE.DAILY,
//         DISP=(NEW,CATLG,DELETE),
//         UNIT=SYSDA,SPACE=(CYL,(30,10),RLSE),
//         DCB=(RECFM=FB,LRECL=150,BLKSIZE=0)
//SYSTSIN  DD *
  DSN SYSTEM(DBPD)
  RUN PROGRAM(DSNTEP2) PLAN(DSNTEP12)
  END
/*
//SYSIN    DD *
  SELECT POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE,
         DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE,
         PREMIUM, EFFECTIVE_DATE, EXPIRY_DATE, STATUS
  FROM COVERAGES
  WHERE STATUS = 'AC'
  ORDER BY POLICY_NUMBER, SEQUENCE_NUM;
/*
//*
//*-------------------------------------------------------------------
//* STEP030 - FTPS (TLS required) extract files to Claims server
//*           Login comes from the RACF-protected NETRC dataset; no
//*           credentials are coded in this job.
//*-------------------------------------------------------------------
//STEP030  EXEC PGM=FTP,PARM='-r TLS (EXIT',
//         COND=(4,LT)
//NETRC    DD DSN=ACME.PAS.FTPS.NETRC,DISP=SHR
//SYSFTPD  DD *
SECURE_FTP        REQUIRED
SECURE_MECHANISM  TLS
TLSMECHANISM      FTP
TLSRFCLEVEL       RFC4217
SECURE_CTRLCONN   PRIVATE
SECURE_DATACONN   PRIVATE
KEYRING           PASFTPS
/*
//INPUT    DD *
 CLAIMSRV.ACME.LOCAL
 PUT 'ACME.PAS.EXTRACT.POLICY.DAILY' /claims/import/policy_extract.dat
 PUT 'ACME.PAS.EXTRACT.COVERAGE.DAILY' /claims/import/coverage_extract.dat
 QUIT
/*
//OUTPUT   DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//*
