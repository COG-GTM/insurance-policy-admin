package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.underwriting_decisions. */
@Embeddable
public class UnderwritingDecisionId implements Serializable {

  private static final long serialVersionUID = 1L;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @Column(name = "decision_date", nullable = false)
  private LocalDate decisionDate;

  protected UnderwritingDecisionId() {}

  public UnderwritingDecisionId(String policyNumber, LocalDate decisionDate) {
    this.policyNumber = policyNumber;
    this.decisionDate = decisionDate;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public LocalDate getDecisionDate() {
    return decisionDate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof UnderwritingDecisionId other)) {
      return false;
    }
    return Objects.equals(policyNumber, other.policyNumber)
        && Objects.equals(decisionDate, other.decisionDate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(policyNumber, decisionDate);
  }

  @Override
  public String toString() {
    return "UnderwritingDecisionId["
        + "policyNumber="
        + policyNumber
        + ", "
        + "decisionDate="
        + decisionDate
        + "]";
  }
}
