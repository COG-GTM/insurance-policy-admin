package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.endorsements. */
@Embeddable
public class EndorsementId implements Serializable {

  private static final long serialVersionUID = 1L;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @Column(name = "endorsement_seq", nullable = false)
  private Integer endorsementSeq;

  protected EndorsementId() {}

  public EndorsementId(String policyNumber, Integer endorsementSeq) {
    this.policyNumber = policyNumber;
    this.endorsementSeq = endorsementSeq;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public Integer getEndorsementSeq() {
    return endorsementSeq;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EndorsementId other)) {
      return false;
    }
    return Objects.equals(policyNumber, other.policyNumber)
        && Objects.equals(endorsementSeq, other.endorsementSeq);
  }

  @Override
  public int hashCode() {
    return Objects.hash(policyNumber, endorsementSeq);
  }

  @Override
  public String toString() {
    return "EndorsementId["
        + "policyNumber="
        + policyNumber
        + ", "
        + "endorsementSeq="
        + endorsementSeq
        + "]";
  }
}
