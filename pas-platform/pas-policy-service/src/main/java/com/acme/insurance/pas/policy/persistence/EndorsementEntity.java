package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** pas.endorsements (ACMEINS.ENDORSEMENTS). */
@Entity
@Table(name = "endorsements")
public class EndorsementEntity {

  @EmbeddedId private EndorsementId id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "endorsement_type", length = 3, nullable = false)
  private String endorsementType;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "description", length = 100)
  private String description;

  @Column(name = "premium_adjustment", precision = 11, scale = 2)
  private BigDecimal premiumAdjustment;

  @Column(name = "processed_date", nullable = false)
  private LocalDateTime processedDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "processed_by", length = 8, nullable = false)
  private String processedBy;

  public EndorsementId getId() {
    return id;
  }

  public void setId(EndorsementId id) {
    this.id = id;
  }

  public String getEndorsementType() {
    return endorsementType;
  }

  public void setEndorsementType(String endorsementType) {
    this.endorsementType = endorsementType;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getPremiumAdjustment() {
    return premiumAdjustment;
  }

  public void setPremiumAdjustment(BigDecimal premiumAdjustment) {
    this.premiumAdjustment = premiumAdjustment;
  }

  public LocalDateTime getProcessedDate() {
    return processedDate;
  }

  public void setProcessedDate(LocalDateTime processedDate) {
    this.processedDate = processedDate;
  }

  public String getProcessedBy() {
    return processedBy;
  }

  public void setProcessedBy(String processedBy) {
    this.processedBy = processedBy;
  }

  @Override
  public String toString() {
    return "EndorsementEntity[id=" + id + "]";
  }
}
