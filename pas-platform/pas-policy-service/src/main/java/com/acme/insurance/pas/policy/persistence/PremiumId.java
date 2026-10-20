package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.premiums. */
@Embeddable
public class PremiumId implements Serializable {

  private static final long serialVersionUID = 1L;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @Column(name = "coverage_seq", nullable = false)
  private Short coverageSeq;

  @Column(name = "term_effective_date", nullable = false)
  private LocalDate termEffectiveDate;

  protected PremiumId() {}

  public PremiumId(String policyNumber, Short coverageSeq, LocalDate termEffectiveDate) {
    this.policyNumber = policyNumber;
    this.coverageSeq = coverageSeq;
    this.termEffectiveDate = termEffectiveDate;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public Short getCoverageSeq() {
    return coverageSeq;
  }

  public LocalDate getTermEffectiveDate() {
    return termEffectiveDate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PremiumId other)) {
      return false;
    }
    return Objects.equals(policyNumber, other.policyNumber)
        && Objects.equals(coverageSeq, other.coverageSeq)
        && Objects.equals(termEffectiveDate, other.termEffectiveDate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(policyNumber, coverageSeq, termEffectiveDate);
  }

  @Override
  public String toString() {
    return "PremiumId["
        + "policyNumber="
        + policyNumber
        + ", "
        + "coverageSeq="
        + coverageSeq
        + ", "
        + "termEffectiveDate="
        + termEffectiveDate
        + "]";
  }
}
