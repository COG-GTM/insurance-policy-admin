package com.acme.insurance.pas.underwriting;

import java.util.Optional;

/**
 * Reads the policy and its holder for underwriting. Empty when either is missing, or when the
 * caller may not see the policy (the API answers both the same way).
 */
public interface UnderwritingSubjects {

  Optional<UnderwritingSubject> find(String policyNumber);
}
