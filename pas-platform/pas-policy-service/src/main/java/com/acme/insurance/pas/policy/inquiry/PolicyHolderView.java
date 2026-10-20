package com.acme.insurance.pas.policy.inquiry;

import com.acme.insurance.pas.common.pii.PiiMasking;
import com.acme.insurance.pas.policy.persistence.PolicyHolderEntity;

/**
 * {@code GET /api/v1/policies/{policyNumber}/holder}: the policyholder block POLQRY reads. New in
 * v1 (the facade never exposed it). Without {@code policy:pii:read} every PII field is masked with
 * the AB-521 {@link PiiMasking} helpers and {@code masked} is {@code true}.
 */
public record PolicyHolderView(
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
    String ssnLast4,
    String taxId,
    String creditScore,
    String riskTier,
    boolean masked) {

  static PolicyHolderView unmasked(PolicyHolderEntity h) {
    return new PolicyHolderView(
        Trim.of(h.getCustId()),
        Trim.of(h.getCustType()),
        Trim.of(h.getLastName()),
        Trim.of(h.getFirstName()),
        Trim.of(h.getMiddleInit()),
        Trim.of(h.getCompanyName()),
        Trim.of(h.getAddrLine1()),
        Trim.of(h.getAddrLine2()),
        Trim.of(h.getCity()),
        Trim.of(h.getStateCode()),
        Trim.of(h.getZipCode()),
        Trim.of(h.getCountryCode()),
        Trim.of(h.getPhone()),
        Trim.of(h.getEmail()),
        h.getDateOfBirth() == null ? null : h.getDateOfBirth().toString(),
        Trim.of(h.getSsnLast4()),
        Trim.of(h.getTaxId()),
        h.getCreditScore() == null ? null : h.getCreditScore().toString(),
        Trim.of(h.getRiskTier()),
        false);
  }

  static PolicyHolderView masked(PolicyHolderEntity h) {
    return new PolicyHolderView(
        Trim.of(h.getCustId()),
        Trim.of(h.getCustType()),
        PiiMasking.name(Trim.of(h.getLastName())),
        PiiMasking.name(Trim.of(h.getFirstName())),
        PiiMasking.name(Trim.of(h.getMiddleInit())),
        PiiMasking.name(Trim.of(h.getCompanyName())),
        PiiMasking.redact(Trim.of(h.getAddrLine1())),
        PiiMasking.redact(Trim.of(h.getAddrLine2())),
        PiiMasking.redact(Trim.of(h.getCity())),
        Trim.of(h.getStateCode()),
        PiiMasking.postalCode(Trim.of(h.getZipCode())),
        Trim.of(h.getCountryCode()),
        PiiMasking.phone(Trim.of(h.getPhone())),
        PiiMasking.email(Trim.of(h.getEmail())),
        PiiMasking.dateOfBirth(h.getDateOfBirth()),
        PiiMasking.ssnLast4(Trim.of(h.getSsnLast4())),
        PiiMasking.taxId(Trim.of(h.getTaxId())),
        PiiMasking.creditScore(h.getCreditScore()),
        Trim.of(h.getRiskTier()),
        true);
  }
}
