package com.acme.insurance.pas.policy.rating;

import java.time.LocalDate;

/** Term inputs for a premium calculation (the fields PREMBAT reads). */
public record PolicyTerm(String policyType, LocalDate effectiveDate, LocalDate expiryDate) {}
