package com.acme.insurance.pas.domain;

import com.acme.insurance.pas.pii.Sensitive;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PolicyHolder(
    @Sensitive(Sensitive.Category.PERSONAL) String custId,
    Coded<CustomerType> custType,
    @Sensitive(Sensitive.Category.PERSONAL) String lastName,
    @Sensitive(Sensitive.Category.PERSONAL) String firstName,
    @Sensitive(Sensitive.Category.PERSONAL) String middleInit,
    @Sensitive(Sensitive.Category.PERSONAL) String companyName,
    @Sensitive(Sensitive.Category.PERSONAL) String addrLine1,
    @Sensitive(Sensitive.Category.PERSONAL) String addrLine2,
    @Sensitive(Sensitive.Category.PERSONAL) String city,
    @Sensitive(Sensitive.Category.PERSONAL) String stateCode,
    @Sensitive(Sensitive.Category.PERSONAL) String zipCode,
    String countryCode,
    @Sensitive(Sensitive.Category.PERSONAL) String phone,
    @Sensitive(Sensitive.Category.PERSONAL) String email,
    @Sensitive(Sensitive.Category.REGULATED) LocalDate dateOfBirth,
    @Sensitive(Sensitive.Category.REGULATED) String ssnLast4,
    @Sensitive(Sensitive.Category.REGULATED) String taxId,
    @Sensitive(Sensitive.Category.REGULATED) Integer creditScore,
    Coded<RiskTier> riskTier,
    Coded<YesNo> gdprConsent,
    LocalDate createdDate,
    OffsetDateTime lastUpdated) {
  public PolicyHolder {
    lastUpdated = Scales.utc(lastUpdated);
  }

  @Override
  public String toString() {
    return "PolicyHolder[custId="
        + masked(custId)
        + ", custType="
        + custType
        + ", lastName="
        + masked(lastName)
        + ", firstName="
        + masked(firstName)
        + ", middleInit="
        + masked(middleInit)
        + ", companyName="
        + masked(companyName)
        + ", addrLine1="
        + masked(addrLine1)
        + ", addrLine2="
        + masked(addrLine2)
        + ", city="
        + masked(city)
        + ", stateCode="
        + masked(stateCode)
        + ", zipCode="
        + masked(zipCode)
        + ", countryCode="
        + countryCode
        + ", phone="
        + masked(phone)
        + ", email="
        + masked(email)
        + ", dateOfBirth="
        + masked(dateOfBirth)
        + ", ssnLast4="
        + masked(ssnLast4)
        + ", taxId="
        + masked(taxId)
        + ", creditScore="
        + masked(creditScore)
        + ", riskTier="
        + riskTier
        + ", gdprConsent="
        + gdprConsent
        + ", createdDate="
        + createdDate
        + ", lastUpdated="
        + lastUpdated
        + "]";
  }

  private static String masked(Object value) {
    return value == null ? "null" : "****";
  }
}
