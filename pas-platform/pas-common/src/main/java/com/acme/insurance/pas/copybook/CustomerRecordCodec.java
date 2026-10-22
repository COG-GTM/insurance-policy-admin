package com.acme.insurance.pas.copybook;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.YesNo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Customer codec carries regulated PII in clear only in memory at the legacy boundary. */
public final class CustomerRecordCodec {
  private final CopybookCodec codec;

  public CustomerRecordCodec(CopybookCharset charset) {
    codec = new CopybookCodec(PasCopybooks.CUSTOMER_RECORD, charset);
  }

  public PolicyHolder decode(byte[] bytes) {
    CopybookCodec.RecordReader reader = codec.reader(bytes);
    return new PolicyHolder(
        reader.string("CUST-ID"),
        Coded.of(CustomerType.class, reader.string("CUST-TYPE")),
        reader.string("CUST-LAST-NAME"),
        reader.string("CUST-FIRST-NAME"),
        reader.string("CUST-MIDDLE-INIT"),
        reader.string("CUST-COMPANY-NAME"),
        reader.string("CUST-ADDR-LINE1"),
        reader.string("CUST-ADDR-LINE2"),
        reader.string("CUST-CITY"),
        reader.string("CUST-STATE"),
        reader.string("CUST-ZIP"),
        reader.string("CUST-COUNTRY"),
        reader.string("CUST-PHONE"),
        reader.string("CUST-EMAIL"),
        reader.date("CUST-DOB"),
        reader.string("CUST-SSN-LAST4"),
        reader.string("CUST-TAX-ID"),
        reader.integer("CUST-CREDIT-SCORE"),
        Coded.of(RiskTier.class, reader.string("CUST-RISK-TIER")),
        Coded.of(YesNo.class, reader.string("CUST-GDPR-CONSENT")),
        reader.date("CUST-CREATED-DATE"),
        atUtc(reader.date("CUST-LAST-UPDATED")));
  }

  public byte[] encode(PolicyHolder value) {
    return codec
        .writer()
        .putString("CUST-ID", value.custId())
        .putString("CUST-TYPE", raw(value.custType()))
        .putString("CUST-LAST-NAME", value.lastName())
        .putString("CUST-FIRST-NAME", value.firstName())
        .putString("CUST-MIDDLE-INIT", value.middleInit())
        .putString("CUST-COMPANY-NAME", value.companyName())
        .putString("CUST-ADDR-LINE1", value.addrLine1())
        .putString("CUST-ADDR-LINE2", value.addrLine2())
        .putString("CUST-CITY", value.city())
        .putString("CUST-STATE", value.stateCode())
        .putString("CUST-ZIP", value.zipCode())
        .putString("CUST-COUNTRY", value.countryCode())
        .putString("CUST-PHONE", value.phone())
        .putString("CUST-EMAIL", value.email())
        .putDate("CUST-DOB", value.dateOfBirth())
        .putString("CUST-SSN-LAST4", value.ssnLast4())
        .putString("CUST-TAX-ID", value.taxId())
        .putDecimal(
            "CUST-CREDIT-SCORE",
            value.creditScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(value.creditScore()))
        .putString("CUST-RISK-TIER", raw(value.riskTier()))
        .putString("CUST-GDPR-CONSENT", raw(value.gdprConsent()))
        .putDate("CUST-CREATED-DATE", value.createdDate())
        .putDate(
            "CUST-LAST-UPDATED",
            value.lastUpdated() == null ? null : value.lastUpdated().toLocalDate())
        .toByteArray();
  }

  private static String raw(Coded<?> coded) {
    return coded == null ? null : coded.raw();
  }

  private static OffsetDateTime atUtc(LocalDate date) {
    return date == null ? null : date.atStartOfDay().atOffset(ZoneOffset.UTC);
  }
}
