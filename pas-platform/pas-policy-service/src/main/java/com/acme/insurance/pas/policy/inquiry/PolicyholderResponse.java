package com.acme.insurance.pas.policy.inquiry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
  "policyNumber",
  "custId",
  "custType",
  "lastName",
  "firstName",
  "middleInit",
  "companyName",
  "addrLine1",
  "addrLine2",
  "city",
  "stateCode",
  "zipCode",
  "countryCode",
  "phone",
  "email",
  "dateOfBirth",
  "birthYear",
  "ssnLast4",
  "taxId",
  "creditScore",
  "riskTier",
  "gdprConsent",
  "piiMasked"
})
public record PolicyholderResponse(
    String policyNumber,
    String custId,
    String custType,
    String lastName,
    String firstName,
    String middleInit,
    String companyName,
    String addrLine1,
    String addrLine2,
    String city,
    String stateCode,
    String zipCode,
    String countryCode,
    String phone,
    String email,
    String dateOfBirth,
    Integer birthYear,
    String ssnLast4,
    String taxId,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer creditScore,
    String riskTier,
    String gdprConsent,
    boolean piiMasked) {}
