package com.acme.insurance.pas.persistence;

import java.time.LocalDate;

/** The POLICIES columns the premium recalculation (PREMBAT POL_CURSOR) reads for one policy. */
public record ActivePolicy(
    String policyNumber, String policyType, LocalDate effectiveDate, LocalDate expiryDate) {}
