package com.acme.insurance.pas.domain.copybook;

import com.acme.insurance.pas.domain.PolicyHolder;
import java.nio.charset.Charset;

/**
 * CUSTOMER-RECORD (365 bytes), mapped to ACMEINS.POLICY_HOLDERS. The record carries PII in clear
 * text; it must only travel over encrypted channels and is never logged (docs/pas/data-model.md
 * section 5).
 */
public final class CustomerRecordCodec extends CopybookCodec<PolicyHolder> {

  public CustomerRecordCodec() {
    this(EBCDIC);
  }

  public CustomerRecordCodec(Charset charset) {
    super(CopybookLayouts.CUSTOMER_RECORD, charset);
  }

  @Override
  protected PolicyHolder read(RecordReader in) {
    return new PolicyHolder(
        in.text("CUST-ID"),
        in.text("CUST-TYPE"),
        in.text("CUST-LAST-NAME"),
        in.text("CUST-FIRST-NAME"),
        in.text("CUST-MIDDLE-INIT"),
        in.text("CUST-COMPANY-NAME"),
        in.text("CUST-ADDR-LINE1"),
        in.text("CUST-ADDR-LINE2"),
        in.text("CUST-CITY"),
        in.text("CUST-STATE"),
        in.text("CUST-ZIP"),
        in.text("CUST-COUNTRY"),
        in.text("CUST-PHONE"),
        in.text("CUST-EMAIL"),
        in.date("CUST-DOB"),
        in.text("CUST-SSN-LAST4"),
        in.text("CUST-TAX-ID"),
        in.number("CUST-CREDIT-SCORE"),
        in.text("CUST-RISK-TIER"),
        in.text("CUST-GDPR-CONSENT"),
        in.date("CUST-CREATED-DATE"),
        in.dateAsTimestamp("CUST-LAST-UPDATED"));
  }

  @Override
  protected void write(PolicyHolder h, RecordWriter out) {
    out.text("CUST-ID", h.custId())
        .text("CUST-TYPE", h.custTypeCode())
        .text("CUST-LAST-NAME", h.lastName())
        .text("CUST-FIRST-NAME", h.firstName())
        .text("CUST-MIDDLE-INIT", h.middleInit())
        .text("CUST-COMPANY-NAME", h.companyName())
        .text("CUST-ADDR-LINE1", h.addrLine1())
        .text("CUST-ADDR-LINE2", h.addrLine2())
        .text("CUST-CITY", h.city())
        .text("CUST-STATE", h.stateCode())
        .text("CUST-ZIP", h.zipCode())
        .text("CUST-COUNTRY", h.countryCode())
        .text("CUST-PHONE", h.phone())
        .text("CUST-EMAIL", h.email())
        .date("CUST-DOB", h.dateOfBirth())
        .text("CUST-SSN-LAST4", h.ssnLast4())
        .text("CUST-TAX-ID", h.taxId())
        .number("CUST-CREDIT-SCORE", h.creditScore())
        .text("CUST-RISK-TIER", h.riskTierCode())
        .text("CUST-GDPR-CONSENT", h.gdprConsent())
        .date("CUST-CREATED-DATE", h.createdDate())
        .dateFromTimestamp("CUST-LAST-UPDATED", h.lastUpdated());
  }
}
