package com.acme.insurance.pas.domain;

/** RS/RM are written by UNDWRT but are not present in the copybook 88-levels (Q-23). */
public enum UnderwritingStatus implements CobolCode {
  APPROVED("AP"),
  PENDING("PN"),
  DECLINED("DC"),
  REFERRED("RF"),
  REVIEW_REQUIRED("RS"),
  REFERRED_MANUAL("RM");

  private final String code;

  UnderwritingStatus(String code) {
    this.code = code;
  }

  @Override
  public String code() {
    return code;
  }
}
