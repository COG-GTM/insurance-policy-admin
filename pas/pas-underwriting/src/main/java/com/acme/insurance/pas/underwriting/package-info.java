/**
 * Underwriting decision ported from UNDWRT (CICS PUWR, AB-525): risk score, branch accumulation,
 * decision bands and manual overrides. Policy, claims and accumulation data come in through the
 * ports {@link com.acme.insurance.pas.underwriting.UnderwritingSubjects}, {@link
 * com.acme.insurance.pas.underwriting.ClaimsHistory} and {@link
 * com.acme.insurance.pas.underwriting.BranchExposure}; nothing here reads a database. May depend on
 * pas-domain only.
 */
package com.acme.insurance.pas.underwriting;
