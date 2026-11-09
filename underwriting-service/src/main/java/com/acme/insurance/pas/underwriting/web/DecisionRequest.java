package com.acme.insurance.pas.underwriting.web;

/** UWRTMAP input: POLICY-NUMBER PIC X(12). */
public record DecisionRequest(String policyNumber) {
}
