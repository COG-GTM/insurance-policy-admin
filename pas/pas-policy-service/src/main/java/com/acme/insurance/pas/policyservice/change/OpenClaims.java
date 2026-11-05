package com.acme.insurance.pas.policyservice.change;

/**
 * Number of open claims on a policy, read through the interface the Claims team owns (FU-17).
 * POLRNW reads the count straight from the Claims table and ignores it (Q-02); the service only
 * asks when {@code pas.policy-change.max-open-claims} is set.
 */
@FunctionalInterface
public interface OpenClaims {

  int openClaimCount(String policyNumber);
}
