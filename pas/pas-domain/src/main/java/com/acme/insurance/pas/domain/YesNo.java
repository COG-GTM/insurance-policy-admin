package com.acme.insurance.pas.domain;

import java.util.Optional;

/** Known values of Y/N indicator fields (POLICY-WEB-IND, POLICY-API-FLAG, CUST-GDPR-CONSENT). */
public enum YesNo implements LegacyCode {
  YES("Y"),
  NO("N");

  private final String code;

  YesNo(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }

  public static Optional<YesNo> fromCode(String code) {
    return LegacyCode.decode(YesNo.class, code);
  }
}
