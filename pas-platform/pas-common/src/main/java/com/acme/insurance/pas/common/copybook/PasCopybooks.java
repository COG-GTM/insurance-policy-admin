package com.acme.insurance.pas.common.copybook;

import static com.acme.insurance.pas.common.copybook.CopybookField.alphanumeric;
import static com.acme.insurance.pas.common.copybook.CopybookField.date;
import static com.acme.insurance.pas.common.copybook.CopybookField.filler;
import static com.acme.insurance.pas.common.copybook.CopybookField.packed;
import static com.acme.insurance.pas.common.copybook.CopybookField.zoned;

import java.nio.charset.Charset;

/**
 * Layouts of the PAS copybooks in {@code cobol/copybooks}. Field names, order and sizes must match
 * the .cpy sources exactly; {@code PasCopybooksDriftTest} parses the real copybooks to enforce it.
 */
public final class PasCopybooks {

  /** EBCDIC code page used by the z/OS host. */
  public static final Charset HOST_CHARSET = Charset.forName("IBM037");

  /** POLICY-RECORD.cpy (DB2 table POLICIES). */
  public static final RecordLayout POLICY_RECORD =
      RecordLayout.builder("POLICY-RECORD")
          .field(alphanumeric("POLICY-NUMBER", 12))
          .field(alphanumeric("POLICY-TYPE", 3))
          .field(alphanumeric("POLICY-STATUS", 2))
          .field(date("POLICY-EFFECTIVE-DATE"))
          .field(date("POLICY-EXPIRY-DATE"))
          .field(alphanumeric("POLICY-HOLDER-ID", 10))
          .field(alphanumeric("POLICY-AGENT-CODE", 6))
          .field(alphanumeric("POLICY-BRANCH-CODE", 4))
          .field(packed("POLICY-TOTAL-PREMIUM", 9, 2))
          .field(packed("POLICY-DEDUCTIBLE", 7, 2))
          .field(packed("POLICY-LIMIT", 11, 2))
          .field(date("POLICY-INCEPTION-DATE"))
          .field(zoned("POLICY-RENEWAL-COUNT", 3))
          .field(alphanumeric("POLICY-UW-STATUS", 2))
          .field(zoned("POLICY-RISK-SCORE", 3))
          .field(alphanumeric("POLICY-WEB-IND", 1))
          .field(alphanumeric("POLICY-API-FLAG", 1))
          .field(date("POLICY-LAST-UPDATED"))
          .field(alphanumeric("POLICY-UPDATED-BY", 8))
          .field(filler(20))
          .build();

  /** COVERAGE-RECORD.cpy (DB2 table COVERAGES). */
  public static final RecordLayout COVERAGE_RECORD =
      RecordLayout.builder("COVERAGE-RECORD")
          .field(alphanumeric("COV-POLICY-NUMBER", 12))
          .field(zoned("COV-SEQUENCE-NUM", 3))
          .field(alphanumeric("COV-TYPE-CODE", 4))
          .field(alphanumeric("COV-DESCRIPTION", 40))
          .field(packed("COV-LIMIT", 11, 2))
          .field(packed("COV-DEDUCTIBLE", 7, 2))
          .field(packed("COV-PREMIUM", 9, 2))
          .field(date("COV-EFFECTIVE-DATE"))
          .field(date("COV-EXPIRY-DATE"))
          .field(alphanumeric("COV-STATUS", 2))
          .field(zoned("COV-COINSURANCE-PCT", 3))
          .field(alphanumeric("COV-RATING-TERRITORY", 6))
          .field(alphanumeric("COV-CLASS-CODE", 5))
          .field(filler(15))
          .build();

  /** PREMIUM-RECORD.cpy (DB2 table PREMIUMS). */
  public static final RecordLayout PREMIUM_RECORD =
      RecordLayout.builder("PREMIUM-RECORD")
          .field(alphanumeric("PREM-POLICY-NUMBER", 12))
          .field(zoned("PREM-COVERAGE-SEQ", 3))
          .field(date("PREM-TERM-EFF-DATE"))
          .field(date("PREM-TERM-EXP-DATE"))
          .field(packed("PREM-BASE-RATE", 7, 4))
          .field(packed("PREM-TERRITORY-FACTOR", 3, 4))
          .field(packed("PREM-CLASS-FACTOR", 3, 4))
          .field(packed("PREM-EXPERIENCE-MOD", 3, 4))
          .field(packed("PREM-SCHEDULE-MOD", 3, 4))
          .field(packed("PREM-DISCOUNT-PCT", 3, 2))
          .field(packed("PREM-SURCHARGE-AMT", 7, 2))
          .field(packed("PREM-TAX-AMT", 7, 2))
          .field(packed("PREM-TOTAL-PREMIUM", 9, 2))
          .field(alphanumeric("PREM-INSTALLMENT-CODE", 2))
          .field(packed("PREM-INSTALLMENT-AMT", 7, 2))
          .field(date("PREM-CALC-DATE"))
          .field(alphanumeric("PREM-CALC-BY", 8))
          .field(filler(10))
          .build();

  /** CUSTOMER-RECORD.cpy (DB2 table POLICY_HOLDERS). Contains regulated PII. */
  public static final RecordLayout CUSTOMER_RECORD =
      RecordLayout.builder("CUSTOMER-RECORD")
          .field(alphanumeric("CUST-ID", 10))
          .field(alphanumeric("CUST-TYPE", 1))
          .field(alphanumeric("CUST-LAST-NAME", 30))
          .field(alphanumeric("CUST-FIRST-NAME", 20))
          .field(alphanumeric("CUST-MIDDLE-INIT", 1))
          .field(alphanumeric("CUST-COMPANY-NAME", 50))
          .field(alphanumeric("CUST-ADDR-LINE1", 40))
          .field(alphanumeric("CUST-ADDR-LINE2", 40))
          .field(alphanumeric("CUST-CITY", 25))
          .field(alphanumeric("CUST-STATE", 2))
          .field(alphanumeric("CUST-ZIP", 10))
          .field(alphanumeric("CUST-COUNTRY", 3))
          .field(alphanumeric("CUST-PHONE", 15))
          .field(alphanumeric("CUST-EMAIL", 60))
          .field(date("CUST-DOB"))
          .field(alphanumeric("CUST-SSN-LAST4", 4))
          .field(alphanumeric("CUST-TAX-ID", 10))
          .field(zoned("CUST-CREDIT-SCORE", 3))
          .field(alphanumeric("CUST-RISK-TIER", 1))
          .field(alphanumeric("CUST-GDPR-CONSENT", 1))
          .field(date("CUST-CREATED-DATE"))
          .field(date("CUST-LAST-UPDATED"))
          .field(filler(15))
          .build();

  private PasCopybooks() {}
}
