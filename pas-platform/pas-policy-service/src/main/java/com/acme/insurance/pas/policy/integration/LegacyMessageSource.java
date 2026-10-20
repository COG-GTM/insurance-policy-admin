package com.acme.insurance.pas.policy.integration;

import java.util.List;

/**
 * The CICS transaction whose {@code POLICY-RECORD} message a bridge reproduces, and which copybook
 * fields that transaction had populated when it wrote the message (parity goldens {@code
 * parity/programs/<prog>-msg/}). Fields a program never populates keep their WORKING-STORAGE
 * content: spaces for POLNEW (the RECEIVE map stub blanks the record), zeros/spaces for the others.
 */
public enum LegacyMessageSource {
  /** WRITEQ TD ACME.PAS.UNDERWRITING.REQUEST after the POLICIES/COVERAGES inserts. */
  POLNEW(
      true,
      "POLICY-NUMBER",
      "POLICY-TYPE",
      "POLICY-EFFECTIVE-DATE",
      "POLICY-EXPIRY-DATE",
      "POLICY-HOLDER-ID",
      "POLICY-AGENT-CODE",
      "POLICY-BRANCH-CODE",
      "POLICY-TOTAL-PREMIUM",
      "POLICY-DEDUCTIBLE",
      "POLICY-LIMIT"),
  /** POLRNW writes no queue; its renewed POLICY-RECORD (SEND MAP POLRCONF) is the payload. */
  POLRNW(
      false,
      "POLICY-NUMBER",
      "POLICY-TYPE",
      "POLICY-STATUS",
      "POLICY-TOTAL-PREMIUM",
      "POLICY-EFFECTIVE-DATE",
      "POLICY-EXPIRY-DATE",
      "POLICY-RENEWAL-COUNT",
      "POLICY-UW-STATUS"),
  /** WRITEQ TS ENDORSEMENT-AUDIT after the premium update. */
  POLEND(
      false,
      "POLICY-NUMBER",
      "POLICY-STATUS",
      "POLICY-EFFECTIVE-DATE",
      "POLICY-EXPIRY-DATE",
      "POLICY-TOTAL-PREMIUM"),
  /** WRITEQ TD ACME.PAS.UNDERWRITING.DECISION after the decision insert and policy update. */
  UNDWRT(
      false,
      "POLICY-NUMBER",
      "POLICY-TYPE",
      "POLICY-TOTAL-PREMIUM",
      "POLICY-LIMIT",
      "POLICY-BRANCH-CODE",
      "POLICY-RISK-SCORE",
      "POLICY-UW-STATUS");

  private final boolean unsetFieldsBlank;
  private final List<String> populatedFields;

  LegacyMessageSource(boolean unsetFieldsBlank, String... populatedFields) {
    this.unsetFieldsBlank = unsetFieldsBlank;
    this.populatedFields = List.of(populatedFields);
  }

  /** True when fields the program never moves hold spaces (even numeric/COMP-3 ones). */
  public boolean unsetFieldsBlank() {
    return unsetFieldsBlank;
  }

  public List<String> populatedFields() {
    return populatedFields;
  }
}
