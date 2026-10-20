package com.acme.insurance.pas.policy.pii;

/**
 * Domain-separation labels for blind indexes, one per indexed column. Never reuse across columns.
 */
public final class BlindIndexPurpose {

  public static final String TAX_ID = "pas.policy_holders.tax_id";
  public static final String SSN_LAST4 = "pas.policy_holders.ssn_last4";

  private BlindIndexPurpose() {}
}
