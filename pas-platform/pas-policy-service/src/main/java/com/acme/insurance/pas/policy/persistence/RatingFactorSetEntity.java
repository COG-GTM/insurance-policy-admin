package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** pas.rating_factor_sets: names the PARITY and FILED factor sets. */
@Entity
@Table(name = "rating_factor_sets")
public class RatingFactorSetEntity {

  @Id
  @Column(name = "factor_set", length = 16, nullable = false)
  private String factorSet;

  @Column(name = "description", length = 200, nullable = false)
  private String description;

  public String getFactorSet() {
    return factorSet;
  }

  public void setFactorSet(String factorSet) {
    this.factorSet = factorSet;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public String toString() {
    return "RatingFactorSetEntity[factorSet=" + factorSet + "]";
  }
}
