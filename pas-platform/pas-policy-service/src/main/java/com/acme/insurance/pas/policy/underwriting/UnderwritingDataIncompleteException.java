package com.acme.insurance.pas.policy.underwriting;

/**
 * UNDWRT could not read the policy/policyholder data it scores on: the holder row is missing, or a
 * column UNDWRT selects is NULL (DB2 returns -305 for a NULL fetched without an indicator). The
 * message is the COBOL screen text.
 */
public class UnderwritingDataIncompleteException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final String MESSAGE = "POLICY/CUSTOMER DATA NOT FOUND";

  public UnderwritingDataIncompleteException() {
    super(MESSAGE);
  }
}
