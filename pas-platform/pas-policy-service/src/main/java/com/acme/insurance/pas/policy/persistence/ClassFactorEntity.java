package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** pas.class_factors reference data (factor set, class code, effective date). */
@Entity
@Table(name = "class_factors")
public class ClassFactorEntity {

  @EmbeddedId private ClassFactorId id;

  @Column(name = "rating_factor", precision = 7, scale = 4, nullable = false)
  private BigDecimal ratingFactor;

  public ClassFactorId getId() {
    return id;
  }

  public void setId(ClassFactorId id) {
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
    return "ClassFactorEntity[id=" + id + "]";
  }
}
