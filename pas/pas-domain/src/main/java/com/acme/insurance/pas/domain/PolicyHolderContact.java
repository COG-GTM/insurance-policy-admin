package com.acme.insurance.pas.domain;

import com.acme.insurance.pas.domain.Pii.Level;

/**
 * The policyholder fields POLQRY reads for an inquiry (3000-READ-CUSTOMER): identity, name and
 * contact details, and never a RESTRICTED value. {@code masked} says whether the personal values
 * come from acmeins.policy_holders_masked. {@link #toString()} prints no personal data.
 */
public record PolicyHolderContact(
    String custId,
    String custTypeCode,
    @Pii(Level.PERSONAL) String lastName,
    @Pii(Level.PERSONAL) String firstName,
    @Pii(Level.PERSONAL) String companyName,
    @Pii(Level.PERSONAL) String phone,
    @Pii(Level.PERSONAL) String email,
    boolean masked) {

  @Override
  public String toString() {
    return "PolicyHolderContact[custId="
        + custId
        + ", custTypeCode="
        + custTypeCode
        + ", personalData=***, masked="
        + masked
        + "]";
  }
}
