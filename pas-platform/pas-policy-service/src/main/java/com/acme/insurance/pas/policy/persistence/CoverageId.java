package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.coverages. */
@Embeddable
public class CoverageId implements Serializable {

  private static final long serialVersionUID = 1L;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @Column(name = "sequence_num", nullable = false)
  private Short sequenceNum;

  protected CoverageId() {}

  public CoverageId(String policyNumber, Short sequenceNum) {
    this.policyNumber = policyNumber;
    this.sequenceNum = sequenceNum;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public Short getSequenceNum() {
    return sequenceNum;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CoverageId other)) {
      return false;
    }
    return Objects.equals(policyNumber, other.policyNumber)
        && Objects.equals(sequenceNum, other.sequenceNum);
  }

  @Override
  public int hashCode() {
    return Objects.hash(policyNumber, sequenceNum);
  }

  @Override
  public String toString() {
    return "CoverageId["
        + "policyNumber="
        + policyNumber
        + ", "
        + "sequenceNum="
        + sequenceNum
        + "]";
  }
}
