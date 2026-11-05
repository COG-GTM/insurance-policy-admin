package com.acme.insurance.pas.domain.copybook;

import static com.acme.insurance.pas.domain.copybook.CopybookField.comp3;
import static com.acme.insurance.pas.domain.copybook.CopybookField.filler;
import static com.acme.insurance.pas.domain.copybook.CopybookField.nine;
import static com.acme.insurance.pas.domain.copybook.CopybookField.x;

import java.util.List;

/** The four PAS copybooks in cobol/copybooks, item for item. */
public final class CopybookLayouts {

  public static final CopybookLayout POLICY_RECORD =
      new CopybookLayout(
          "POLICY-RECORD",
          List.of(
              x("POLICY-NUMBER", 12),
              x("POLICY-TYPE", 3),
              x("POLICY-STATUS", 2),
              nine("POLICY-EFFECTIVE-DATE", 8),
              nine("POLICY-EXPIRY-DATE", 8),
              x("POLICY-HOLDER-ID", 10),
              x("POLICY-AGENT-CODE", 6),
              x("POLICY-BRANCH-CODE", 4),
              comp3("POLICY-TOTAL-PREMIUM", 9, 2),
              comp3("POLICY-DEDUCTIBLE", 7, 2),
              comp3("POLICY-LIMIT", 11, 2),
              nine("POLICY-INCEPTION-DATE", 8),
              nine("POLICY-RENEWAL-COUNT", 3),
              x("POLICY-UW-STATUS", 2),
              nine("POLICY-RISK-SCORE", 3),
              x("POLICY-WEB-IND", 1),
              x("POLICY-API-FLAG", 1),
              nine("POLICY-LAST-UPDATED", 8),
              x("POLICY-UPDATED-BY", 8),
              filler(20)));

  public static final CopybookLayout COVERAGE_RECORD =
      new CopybookLayout(
          "COVERAGE-RECORD",
          List.of(
              x("COV-POLICY-NUMBER", 12),
              nine("COV-SEQUENCE-NUM", 3),
              x("COV-TYPE-CODE", 4),
              x("COV-DESCRIPTION", 40),
              comp3("COV-LIMIT", 11, 2),
              comp3("COV-DEDUCTIBLE", 7, 2),
              comp3("COV-PREMIUM", 9, 2),
              nine("COV-EFFECTIVE-DATE", 8),
              nine("COV-EXPIRY-DATE", 8),
              x("COV-STATUS", 2),
              nine("COV-COINSURANCE-PCT", 3),
              x("COV-RATING-TERRITORY", 6),
              x("COV-CLASS-CODE", 5),
              filler(15)));

  public static final CopybookLayout PREMIUM_RECORD =
      new CopybookLayout(
          "PREMIUM-RECORD",
          List.of(
              x("PREM-POLICY-NUMBER", 12),
              nine("PREM-COVERAGE-SEQ", 3),
              nine("PREM-TERM-EFF-DATE", 8),
              nine("PREM-TERM-EXP-DATE", 8),
              comp3("PREM-BASE-RATE", 7, 4),
              comp3("PREM-TERRITORY-FACTOR", 3, 4),
              comp3("PREM-CLASS-FACTOR", 3, 4),
              comp3("PREM-EXPERIENCE-MOD", 3, 4),
              comp3("PREM-SCHEDULE-MOD", 3, 4),
              comp3("PREM-DISCOUNT-PCT", 3, 2),
              comp3("PREM-SURCHARGE-AMT", 7, 2),
              comp3("PREM-TAX-AMT", 7, 2),
              comp3("PREM-TOTAL-PREMIUM", 9, 2),
              x("PREM-INSTALLMENT-CODE", 2),
              comp3("PREM-INSTALLMENT-AMT", 7, 2),
              nine("PREM-CALC-DATE", 8),
              x("PREM-CALC-BY", 8),
              filler(10)));

  public static final CopybookLayout CUSTOMER_RECORD =
      new CopybookLayout(
          "CUSTOMER-RECORD",
          List.of(
              x("CUST-ID", 10),
              x("CUST-TYPE", 1),
              x("CUST-LAST-NAME", 30),
              x("CUST-FIRST-NAME", 20),
              x("CUST-MIDDLE-INIT", 1),
              x("CUST-COMPANY-NAME", 50),
              x("CUST-ADDR-LINE1", 40),
              x("CUST-ADDR-LINE2", 40),
              x("CUST-CITY", 25),
              x("CUST-STATE", 2),
              x("CUST-ZIP", 10),
              x("CUST-COUNTRY", 3),
              x("CUST-PHONE", 15),
              x("CUST-EMAIL", 60),
              nine("CUST-DOB", 8),
              x("CUST-SSN-LAST4", 4),
              x("CUST-TAX-ID", 10),
              nine("CUST-CREDIT-SCORE", 3),
              x("CUST-RISK-TIER", 1),
              x("CUST-GDPR-CONSENT", 1),
              nine("CUST-CREATED-DATE", 8),
              nine("CUST-LAST-UPDATED", 8),
              filler(15)));

  public static final List<CopybookLayout> ALL =
      List.of(POLICY_RECORD, COVERAGE_RECORD, PREMIUM_RECORD, CUSTOMER_RECORD);

  private CopybookLayouts() {}
}
