package com.acme.insurance.pas.common.domain;

import static com.acme.insurance.pas.common.domain.PolicyRecord.whole;

import com.acme.insurance.pas.common.copybook.PasCopybooks;
import com.acme.insurance.pas.common.copybook.RecordValues;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Optional;

/**
 * CUSTOMER-RECORD.cpy as a value type. Holds regulated PII (DOB, SSN last 4, tax ID, credit score)
 * plus contact details, so {@link #toString()} only prints the customer id.
 */
public record CustomerRecord(
    String custId,
    String custTypeCode,
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
    LocalDate dateOfBirth,
    String ssnLast4,
    String taxId,
    int creditScore,
    String riskTierCode,
    String gdprConsent,
    LocalDate createdDate,
    LocalDate lastUpdated) {

  public Optional<CustomerType> custType() {
    return CopybookCode.lookup(CustomerType.class, custTypeCode);
  }

  public Optional<RiskTier> riskTier() {
    return CopybookCode.lookup(RiskTier.class, riskTierCode);
  }

  public static CustomerRecord fromBytes(byte[] record, Charset charset) {
    RecordValues v = PasCopybooks.CUSTOMER_RECORD.decode(record, charset);
    return new CustomerRecord(
        v.string("CUST-ID"),
        v.string("CUST-TYPE"),
        v.string("CUST-LAST-NAME"),
        v.string("CUST-FIRST-NAME"),
        v.string("CUST-MIDDLE-INIT"),
        v.string("CUST-COMPANY-NAME"),
        v.string("CUST-ADDR-LINE1"),
        v.string("CUST-ADDR-LINE2"),
        v.string("CUST-CITY"),
        v.string("CUST-STATE"),
        v.string("CUST-ZIP"),
        v.string("CUST-COUNTRY"),
        v.string("CUST-PHONE"),
        v.string("CUST-EMAIL"),
        v.date("CUST-DOB"),
        v.string("CUST-SSN-LAST4"),
        v.string("CUST-TAX-ID"),
        v.integer("CUST-CREDIT-SCORE"),
        v.string("CUST-RISK-TIER"),
        v.string("CUST-GDPR-CONSENT"),
        v.date("CUST-CREATED-DATE"),
        v.date("CUST-LAST-UPDATED"));
  }

  public byte[] toBytes(Charset charset) {
    RecordValues v =
        new RecordValues()
            .put("CUST-ID", custId)
            .put("CUST-TYPE", custTypeCode)
            .put("CUST-LAST-NAME", lastName)
            .put("CUST-FIRST-NAME", firstName)
            .put("CUST-MIDDLE-INIT", middleInit)
            .put("CUST-COMPANY-NAME", companyName)
            .put("CUST-ADDR-LINE1", addrLine1)
            .put("CUST-ADDR-LINE2", addrLine2)
            .put("CUST-CITY", city)
            .put("CUST-STATE", state)
            .put("CUST-ZIP", zip)
            .put("CUST-COUNTRY", country)
            .put("CUST-PHONE", phone)
            .put("CUST-EMAIL", email)
            .put("CUST-DOB", dateOfBirth)
            .put("CUST-SSN-LAST4", ssnLast4)
            .put("CUST-TAX-ID", taxId)
            .put("CUST-CREDIT-SCORE", whole(creditScore))
            .put("CUST-RISK-TIER", riskTierCode)
            .put("CUST-GDPR-CONSENT", gdprConsent)
            .put("CUST-CREATED-DATE", createdDate)
            .put("CUST-LAST-UPDATED", lastUpdated);
    return PasCopybooks.CUSTOMER_RECORD.encode(v, charset);
  }

  @Override
  public String toString() {
    return "CustomerRecord[custId=" + custId + ", pii=REDACTED]";
  }
}
