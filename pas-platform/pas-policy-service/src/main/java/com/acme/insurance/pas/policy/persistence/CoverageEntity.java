package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** pas.coverages (ACMEINS.COVERAGES, COVERAGE-RECORD.cpy). */
@Entity
@Table(name = "coverages")
public class CoverageEntity {

  @EmbeddedId private CoverageId id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "coverage_type", length = 4, nullable = false)
  private String coverageType;

  @Column(name = "description", length = 40)
  private String description;

  @Column(name = "coverage_limit", precision = 13, scale = 2)
  private BigDecimal coverageLimit;

  @Column(name = "deductible", precision = 9, scale = 2)
  private BigDecimal deductible;

  @Column(name = "premium", precision = 11, scale = 2)
  private BigDecimal premium;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDate expiryDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "status", length = 2, nullable = false)
  private String status;

  @Column(name = "coinsurance_pct")
  private Short coinsurancePct;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "rating_territory", length = 6)
  private String ratingTerritory;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "class_code", length = 5)
  private String classCode;

  public CoverageId getId() {
    return id;
  }

  public void setId(CoverageId id) {
    this.id = id;
  }

  public String getCoverageType() {
    return coverageType;
  }

  public void setCoverageType(String coverageType) {
    this.coverageType = coverageType;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getCoverageLimit() {
    return coverageLimit;
  }

  public void setCoverageLimit(BigDecimal coverageLimit) {
    this.coverageLimit = coverageLimit;
  }

  public BigDecimal getDeductible() {
    return deductible;
  }

  public void setDeductible(BigDecimal deductible) {
    this.deductible = deductible;
  }

  public BigDecimal getPremium() {
    return premium;
  }

  public void setPremium(BigDecimal premium) {
    this.premium = premium;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Short getCoinsurancePct() {
    return coinsurancePct;
  }

  public void setCoinsurancePct(Short coinsurancePct) {
    this.coinsurancePct = coinsurancePct;
  }

  public String getRatingTerritory() {
    return ratingTerritory;
  }

  public void setRatingTerritory(String ratingTerritory) {
    this.ratingTerritory = ratingTerritory;
  }

  public String getClassCode() {
    return classCode;
  }

  public void setClassCode(String classCode) {
    this.classCode = classCode;
  }

  @Override
  public String toString() {
    return "CoverageEntity[id=" + id + "]";
  }
}
