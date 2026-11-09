package com.acme.insurance.pas.underwriting.copybook;

import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.alnum;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.date;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.oneOf;
import static com.acme.insurance.pas.underwriting.copybook.CopybookFields.unsigned;

/**
 * {@code CUSTOMER-RECORD} from {@code cobol/copybooks/CUSTOMER-RECORD.cpy} (DB2 table POLICY_HOLDERS).
 * Record length 365 bytes; the trailing {@code FILLER PIC X(15)} is not mapped. The
 * {@code CUST-ADDRESS} group (offset 112, 120 bytes) is flattened.
 *
 * @param custId        CUST-ID PIC X(10), offset 0
 * @param custType      CUST-TYPE PIC X(01), offset 10 (88 I/C/G)
 * @param lastName      CUST-LAST-NAME PIC X(30), offset 11
 * @param firstName     CUST-FIRST-NAME PIC X(20), offset 41
 * @param middleInit    CUST-MIDDLE-INIT PIC X(01), offset 61
 * @param companyName   CUST-COMPANY-NAME PIC X(50), offset 62
 * @param addrLine1     CUST-ADDR-LINE1 PIC X(40), offset 112
 * @param addrLine2     CUST-ADDR-LINE2 PIC X(40), offset 152
 * @param city          CUST-CITY PIC X(25), offset 192
 * @param state         CUST-STATE PIC X(02), offset 217
 * @param zip           CUST-ZIP PIC X(10), offset 219
 * @param country       CUST-COUNTRY PIC X(03), offset 229
 * @param phone         CUST-PHONE PIC X(15), offset 232
 * @param email         CUST-EMAIL PIC X(60), offset 247
 * @param dateOfBirth   CUST-DOB PIC 9(08), offset 307
 * @param ssnLast4      CUST-SSN-LAST4 PIC X(04), offset 315
 * @param taxId         CUST-TAX-ID PIC X(10), offset 319
 * @param creditScore   CUST-CREDIT-SCORE PIC 9(03), offset 329
 * @param riskTier      CUST-RISK-TIER PIC X(01), offset 332 (88 P=PREFERRED, S=STANDARD, U=SUBSTAND)
 * @param gdprConsent   CUST-GDPR-CONSENT PIC X(01), offset 333 (88 Y/N)
 * @param createdDate   CUST-CREATED-DATE PIC 9(08), offset 334
 * @param lastUpdated   CUST-LAST-UPDATED PIC 9(08), offset 342
 */
public record CustomerRecord(
        String custId,
        String custType,
        String lastName,
        String firstName,
        String middleInit,
        String companyName,
        String addrLine1,
        String addrLine2,
        String city,
        String state,
        String zip,
        String country,
        String phone,
        String email,
        int dateOfBirth,
        String ssnLast4,
        String taxId,
        int creditScore,
        String riskTier,
        String gdprConsent,
        int createdDate,
        int lastUpdated) {

    public static final int RECORD_LENGTH = 365;
    public static final int CREDIT_SCORE_DIGITS = 3;

    public CustomerRecord {
        custId = alnum("CUST-ID", custId, 10);
        custType = alnum("CUST-TYPE", custType, 1);
        lastName = alnum("CUST-LAST-NAME", lastName, 30);
        firstName = alnum("CUST-FIRST-NAME", firstName, 20);
        middleInit = alnum("CUST-MIDDLE-INIT", middleInit, 1);
        companyName = alnum("CUST-COMPANY-NAME", companyName, 50);
        addrLine1 = alnum("CUST-ADDR-LINE1", addrLine1, 40);
        addrLine2 = alnum("CUST-ADDR-LINE2", addrLine2, 40);
        city = alnum("CUST-CITY", city, 25);
        state = alnum("CUST-STATE", state, 2);
        zip = alnum("CUST-ZIP", zip, 10);
        country = alnum("CUST-COUNTRY", country, 3);
        phone = alnum("CUST-PHONE", phone, 15);
        email = alnum("CUST-EMAIL", email, 60);
        dateOfBirth = date("CUST-DOB", dateOfBirth);
        ssnLast4 = alnum("CUST-SSN-LAST4", ssnLast4, 4);
        taxId = alnum("CUST-TAX-ID", taxId, 10);
        creditScore = unsigned("CUST-CREDIT-SCORE", creditScore, CREDIT_SCORE_DIGITS);
        riskTier = alnum("CUST-RISK-TIER", riskTier, 1);
        gdprConsent = oneOf("CUST-GDPR-CONSENT", gdprConsent, 1, "Y", "N");
        createdDate = date("CUST-CREATED-DATE", createdDate);
        lastUpdated = date("CUST-LAST-UPDATED", lastUpdated);
    }

    /** The record as UNDWRT holds it after 2000-READ-POLICY-DATA (CUST-TYPE, CREDIT-SCORE, RISK-TIER fetched). */
    public static CustomerRecord fromUnderwritingSelect(String custType, int creditScore, String riskTier) {
        return new CustomerRecord("", custType, "", "", "", "", "", "", "", "", "", "", "", "", 0, "", "",
                creditScore, riskTier, "", 0, 0);
    }

    /** 88 CUST-TIER-PREFERRED VALUE 'P'. */
    public boolean isTierPreferred() {
        return "P".equals(riskTier);
    }

    /** 88 CUST-TIER-SUBSTAND VALUE 'U'. */
    public boolean isTierSubstandard() {
        return "U".equals(riskTier);
    }
}
