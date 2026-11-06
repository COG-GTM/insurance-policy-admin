package com.acme.insurance.pas.policyservice.security;

import java.util.Set;

/**
 * Which part of the book of business a caller may see (ADR 0001 D5, AB-365). A policy is visible
 * when the caller holds the whole book, or the policy's agent code or branch code is in the
 * caller's lists. Anything else is denied, including a caller with no entitlement at all.
 *
 * @param wholeBook back-office and system callers entitled to every policy
 * @param agentCodes agent codes whose policies the caller may see
 * @param branchCodes branch codes whose policies the caller may see
 */
public record Entitlement(boolean wholeBook, Set<String> agentCodes, Set<String> branchCodes) {

  public static final Entitlement NONE = new Entitlement(false, Set.of(), Set.of());

  public Entitlement {
    agentCodes = Set.copyOf(agentCodes);
    branchCodes = Set.copyOf(branchCodes);
  }

  public boolean permits(String agentCode, String branchCode) {
    return wholeBook
        || (agentCode != null && agentCodes.contains(agentCode))
        || (branchCode != null && branchCodes.contains(branchCode));
  }
}
