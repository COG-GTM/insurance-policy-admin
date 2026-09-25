//MONEXP   JOB (ACME,PAS),'MONTHLY EXPOSURE EXTRACT',
//         CLASS=A,MSGCLASS=X,MSGLEVEL=(1,1),
//         NOTIFY=&SYSUID,TIME=(2,)
//*******************************************************************
//* MONTHLY-EXPOSURE.JCL - Monthly Exposure Data for Actuarial
//* System:   Policy Administration System (PAS)
//* Schedule: 1st of month at 02:00 EST (CA-7 Job# PAS0200)
//* Author:   M. Kowalski
//* Date:     1999-03-01
//* Modified: 2008-07-01 - Added catastrophe zone data
//*           2015-01-15 - Added cyber exposure fields
//*           2026-09-25 - Replaced cleartext FTP with SFTP and
//*                        removed the shared ACMEFTP logon
//*
//* Extracts monthly exposure data for actuarial analysis.
//* Output is consumed by SAS programs for loss reserving
//* and pricing models.
//*
//* OUTPUT: ACME.PAS.EXPOSURE.MONTHLY.Dyymmdd
//*******************************************************************
//*
//JOBLIB   DD DSN=ACME.PAS.LOADLIB,DISP=SHR
//         DD DSN=DB2.V12.SDSNLOAD,DISP=SHR
//*
//*-------------------------------------------------------------------
//* STEP010 - Generate exposure summary by line of business
//*-------------------------------------------------------------------
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD DSN=DB2.V12.SDSNLOAD,DISP=SHR
//SYSTSPRT DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//EXPOUT   DD DSN=ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD,
//         DISP=(NEW,CATLG,DELETE),
//         UNIT=SYSDA,SPACE=(CYL,(100,20),RLSE),
//         DCB=(RECFM=FB,LRECL=300,BLKSIZE=0)
//SYSTSIN  DD *
  DSN SYSTEM(DBPD)
  RUN PROGRAM(DSNTEP2) PLAN(DSNTEP12)
  END
/*
//SYSIN    DD *
  SELECT P.POLICY_TYPE,
         P.BRANCH_CODE,
         C.COVERAGE_TYPE,
         C.RATING_TERRITORY,
         C.CLASS_CODE,
         COUNT(*) AS POLICY_COUNT,
         SUM(C.COVERAGE_LIMIT) AS TOTAL_EXPOSURE,
         SUM(C.PREMIUM) AS TOTAL_PREMIUM,
         SUM(C.DEDUCTIBLE) AS TOTAL_DEDUCTIBLE
  FROM POLICIES P
  JOIN COVERAGES C
    ON P.POLICY_NUMBER = C.POLICY_NUMBER
  WHERE P.POLICY_STATUS = 'AC'
    AND C.STATUS = 'AC'
  GROUP BY P.POLICY_TYPE, P.BRANCH_CODE,
           C.COVERAGE_TYPE, C.RATING_TERRITORY,
           C.CLASS_CODE
  ORDER BY P.POLICY_TYPE, P.BRANCH_CODE;
/*
//*
//*-------------------------------------------------------------------
//* STEP020 - Copy to actuarial shared drive over SFTP (z/OS
//*           OpenSSH). Encrypted transport, and no logon
//*           credentials in this member:
//*           - /u/pasxfrm/.ssh/config supplies User PASXFRM, a
//*             least-privilege id permitted only to write to
//*             /actuarial/data on the actuarial server
//*           - its private key lives in the RACF key ring named on
//*             the config IdentityKeyRingLabel keyword; the batch
//*             id must be PERMITted to that ring
//*           - the same config pins the server host key
//*             (StrictHostKeyChecking yes) to block MITM
//*-------------------------------------------------------------------
//STEP020  EXEC PGM=BPXBATCH,REGION=0M,
//         COND=(4,LT,STEP010)
//STDPARM  DD *
SH sftp -b - -F /u/pasxfrm/.ssh/config actuarial.acme.local
/*
//STDIN    DD *,SYMBOLS=JCLONLY
put //'ACME.PAS.EXPOSURE.MONTHLY.D&LYYMMDD' /actuarial/data/exposure_monthly.csv
quit
/*
//STDOUT   DD SYSOUT=*
//STDERR   DD SYSOUT=*
//*
