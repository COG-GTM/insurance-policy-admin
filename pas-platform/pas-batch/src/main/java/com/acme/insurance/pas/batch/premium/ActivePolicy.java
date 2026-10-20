package com.acme.insurance.pas.batch.premium;

import java.time.LocalDate;

/** The POL_CURSOR columns PREMBAT rates with (TOTAL_PREMIUM etc. are fetched but ignored, Q-23). */
public record ActivePolicy(
    String policyNumber, String policyType, LocalDate effectiveDate, LocalDate expiryDate) {}
