package com.acme.insurance.pas.persistence;

/** READ / UPDATED / ERRORS of one premium recalculation run, as PREMRPT prints them. */
public record PremiumRunTotals(long read, long updated, long errors) {}
