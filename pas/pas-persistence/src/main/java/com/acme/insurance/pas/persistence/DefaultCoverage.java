package com.acme.insurance.pas.persistence;

/** PASAPP.DEFAULT_COVERAGES row: a coverage POLNEW creates for a policy type (quirk Q-10). */
public record DefaultCoverage(
    String policyType, int sequenceNum, String coverageType, String description) {}
