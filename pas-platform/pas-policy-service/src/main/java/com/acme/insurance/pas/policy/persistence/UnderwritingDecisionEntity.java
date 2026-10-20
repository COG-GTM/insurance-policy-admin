package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** pas.underwriting_decisions (ACMEINS.UNDERWRITING_DECISIONS). */
@Entity
@Table(name = "underwriting_decisions")
public class UnderwritingDecisionEntity {

  @EmbeddedId private UnderwritingDecisionId id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "decision_code", length = 2, nullable = false)
  private String decisionCode;

  @Column(name = "risk_score")
  private Short riskScore;

  @Column(name = "decision_reason", length = 100)
  private String decisionReason;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "underwriter_id", length = 8)
  private String underwriterId;

  @Column(name = "override_reason", length = 200)
  private String overrideReason;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "override_by", length = 8)
  private String overrideBy;

  @Column(name = "created_timestamp", nullable = false)
  private LocalDateTime createdTimestamp;

  public UnderwritingDecisionId getId() {
    return id;
  }

  public void setId(UnderwritingDecisionId id) {
    this.id = id;
  }

  public String getDecisionCode() {
    return decisionCode;
  }

  public void setDecisionCode(String decisionCode) {
    this.decisionCode = decisionCode;
  }

  public Short getRiskScore() {
    return riskScore;
  }

  public void setRiskScore(Short riskScore) {
    this.riskScore = riskScore;
  }

  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(String decisionReason) {
    this.decisionReason = decisionReason;
  }

  public String getUnderwriterId() {
    return underwriterId;
  }

  public void setUnderwriterId(String underwriterId) {
    this.underwriterId = underwriterId;
  }

  public String getOverrideReason() {
    return overrideReason;
  }

  public void setOverrideReason(String overrideReason) {
    this.overrideReason = overrideReason;
  }

  public String getOverrideBy() {
    return overrideBy;
  }

  public void setOverrideBy(String overrideBy) {
    this.overrideBy = overrideBy;
  }

  public LocalDateTime getCreatedTimestamp() {
    return createdTimestamp;
  }

  public void setCreatedTimestamp(LocalDateTime createdTimestamp) {
    this.createdTimestamp = createdTimestamp;
  }

  @Override
  public String toString() {
    return "UnderwritingDecisionEntity[id=" + id + "]";
  }
}
