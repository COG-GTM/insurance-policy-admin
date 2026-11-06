package com.acme.insurance.pas.domain;

import static com.acme.insurance.pas.domain.Constraints.range;
import static com.acme.insurance.pas.domain.Constraints.required;
import static com.acme.insurance.pas.domain.Constraints.text;

import com.acme.insurance.pas.domain.Pii.Level;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ACMEINS.POLICY_HOLDERS row and CUSTOMER-RECORD copybook. Personal data is annotated with {@link
 * Pii}; {@link #toString()} prints only the non-personal fields so a holder can never leak into a
 * log line by accident.
 */
public record PolicyHolder(
    String custId,
    String custTypeCode,
    @Pii(Level.PERSONAL) String lastName,
    @Pii(Level.PERSONAL) String firstName,
    @Pii(Level.PERSONAL) String middleInit,
    @Pii(Level.PERSONAL) String companyName,
    @Pii(Level.PERSONAL) String addrLine1,
    @Pii(Level.PERSONAL) String addrLine2,
    @Pii(Level.PERSONAL) String city,
    String stateCode,
    @Pii(Level.PERSONAL) String zipCode,
    String countryCode,
    @Pii(Level.PERSONAL) String phone,
    @Pii(Level.PERSONAL) String email,
    @Pii(Level.RESTRICTED) LocalDate dateOfBirth,
    @Pii(Level.RESTRICTED) String ssnLast4,
    @Pii(Level.RESTRICTED) String taxId,
    @Pii(Level.RESTRICTED) Integer creditScore,
    String riskTierCode,
    String gdprConsent,
    LocalDate createdDate,
    LocalDateTime lastUpdated) {

  static final String MASK = "***";

  public PolicyHolder {
    text(required(custId, "custId"), 10, "custId");
    text(custTypeCode, 1, "custTypeCode");
    text(lastName, 30, "lastName");
    text(firstName, 20, "firstName");
    text(middleInit, 1, "middleInit");
    text(companyName, 50, "companyName");
    text(addrLine1, 40, "addrLine1");
    text(addrLine2, 40, "addrLine2");
    text(city, 25, "city");
    text(stateCode, 2, "stateCode");
    text(zipCode, 10, "zipCode");
    text(countryCode, 3, "countryCode");
    text(phone, 15, "phone");
    text(email, 60, "email");
    text(ssnLast4, 4, "ssnLast4");
    text(taxId, 10, "taxId");
    range(creditScore, 999, "creditScore");
    text(riskTierCode, 1, "riskTierCode");
    text(gdprConsent, 1, "gdprConsent");
  }

  public Optional<CustomerType> custType() {
    return CustomerType.fromCode(custTypeCode);
  }

  public Optional<RiskTier> riskTier() {
    return RiskTier.fromCode(riskTierCode);
  }

  @Override
  public String toString() {
    return "PolicyHolder[custId="
        + custId
        + ", custTypeCode="
        + custTypeCode
        + ", personalData="
        + MASK
        + ", riskTierCode="
        + riskTierCode
        + ", gdprConsent="
        + gdprConsent
        + ", createdDate="
        + createdDate
        + ", lastUpdated="
        + lastUpdated
        + "]";
  }
}
