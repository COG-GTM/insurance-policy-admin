package com.acme.insurance.pas.policyservice.inquiry;

import com.acme.insurance.pas.domain.PolicyHolderContact;

/**
 * {@code GET /api/v1/policies/{policyNumber}/policyholder}, new in AB-524: the holder fields POLQRY
 * reads. Personal values are masked unless the caller has {@code pas.policyholder.read}; RESTRICTED
 * values (date of birth, SSN, tax id, credit score) are never returned.
 */
public record PolicyHolderView(
    String custId,
    String custType,
    String lastName,
    String firstName,
    String companyName,
    String phone,
    String email,
    boolean masked) {

  static PolicyHolderView from(PolicyHolderContact c) {
    return new PolicyHolderView(
        c.custId(),
        c.custTypeCode(),
        c.lastName(),
        c.firstName(),
        c.companyName(),
        c.phone(),
        c.email(),
        c.masked());
  }
}
