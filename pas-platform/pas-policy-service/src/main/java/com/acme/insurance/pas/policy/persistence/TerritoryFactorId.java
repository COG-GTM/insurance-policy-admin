package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Key of pas.territory_factors. */
@Embeddable
public class TerritoryFactorId implements Serializable {

  private static final long serialVersionUID = 1L;

  @Column(name = "factor_set", length = 16, nullable = false)
  private String factorSet;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "territory_code", length = 6, nullable = false)
  private String territoryCode;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  protected TerritoryFactorId() {}

  public TerritoryFactorId(String factorSet, String territoryCode, LocalDate effectiveDate) {
    this.factorSet = factorSet;
    this.territoryCode = territoryCode;
    this.effectiveDate = effectiveDate;
  }

  public String getFactorSet() {
    return factorSet;
  }

  public String getTerritoryCode() {
    return territoryCode;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TerritoryFactorId other)) {
      return false;
    }
    return Objects.equals(factorSet, other.factorSet)
        && Objects.equals(territoryCode, other.territoryCode)
        && Objects.equals(effectiveDate, other.effectiveDate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(factorSet, territoryCode, effectiveDate);
  }

  @Override
  public String toString() {
    return "TerritoryFactorId["
        + "factorSet="
        + factorSet
        + ", "
        + "territoryCode="
        + territoryCode
        + ", "
        + "effectiveDate="
        + effectiveDate
        + "]";
  }
}
