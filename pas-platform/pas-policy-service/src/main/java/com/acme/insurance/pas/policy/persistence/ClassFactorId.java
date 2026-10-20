package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.class_factors. */
@Embeddable
public class ClassFactorId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "factor_set", length = 16, nullable = false)
  private String factorSet;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "class_code", length = 5, nullable = false)
  private String classCode;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  protected ClassFactorId() {}

  public ClassFactorId(String factorSet, String classCode, LocalDate effectiveDate) {
    this.factorSet = factorSet;
    this.classCode = classCode;
    this.effectiveDate = effectiveDate;
  }

  public String getFactorSet() {
    return factorSet;
  }

  public String getClassCode() {
    return classCode;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ClassFactorId other)) {
      return false;
    }
    return Objects.equals(factorSet, other.factorSet)
        && Objects.equals(classCode, other.classCode)
        && Objects.equals(effectiveDate, other.effectiveDate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(factorSet, classCode, effectiveDate);
  }

  @Override
  public String toString() {
    return "ClassFactorId["
        + "factorSet="
        + factorSet
        + ", "
        + "classCode="
        + classCode
        + ", "
        + "effectiveDate="
        + effectiveDate
        + "]";
  }
}
