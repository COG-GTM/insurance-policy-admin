package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** pas.territory_factors reference data (factor set, territory, effective date). */
@Entity
@Table(name = "territory_factors")
public class TerritoryFactorEntity {

  @EmbeddedId private TerritoryFactorId id;

  @Column(name = "rating_factor", precision = 7, scale = 4, nullable = false)
  private BigDecimal ratingFactor;

  public TerritoryFactorId getId() {
    return id;
  }

  public void setId(TerritoryFactorId id) {
    this.id = id;
  }

  public BigDecimal getRatingFactor() {
    return ratingFactor;
  }

  public void setRatingFactor(BigDecimal ratingFactor) {
    this.ratingFactor = ratingFactor;
  }

  @Override
  public String toString() {
    return "TerritoryFactorEntity[id=" + id + "]";
  }
}
