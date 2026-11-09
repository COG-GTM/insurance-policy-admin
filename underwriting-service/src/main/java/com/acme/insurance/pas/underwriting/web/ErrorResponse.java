package com.acme.insurance.pas.underwriting.web;

/** 9000-SEND-ERROR: the WS-ERROR-MSG text the CICS transaction sends. */
public record ErrorResponse(String transactionId, String errorMessage) {
}
