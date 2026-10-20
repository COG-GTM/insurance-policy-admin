package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * pas.underwriting_decisions (ACMEINS.UNDERWRITING_DECISIONS). Surrogate {@code decision_id} key so
 * same-day decisions are all kept (quirk register Q-20).
 */
@Entity
@Table(name = "underwriting_decisions")
public class UnderwritingDecisionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "decision_id")
  private Long decisionId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @Column(name = "decision_date", nullable = false)
  private LocalDate decisionDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "decision_code", length = 2, nullable = false)
  private String decisionCode;

  @Column(name = "risk_score")
  private Short riskScore;

  @Column(name = "decision_reason", length = 100)
  private String decisionReason;

  @Column(name = "underwriter_id", length = 255)
  private String underwriterId;

  @Column(name = "override_reason", length = 200)
  private String overrideReason;

  @Column(name = "override_by", length = 255)
  private String overrideBy;

  @Column(name = "created_timestamp", nullable = false)
  private LocalDateTime createdTimestamp;

  public Long getDecisionId() {
    return decisionId;
  }

  public String getPolicyNumber() {
    return policyNumber;
  }

  public void setPolicyNumber(String policyNumber) {
    this.policyNumber = policyNumber;
  }

  public LocalDate getDecisionDate() {
    return decisionDate;
  }

  public void setDecisionDate(LocalDate decisionDate) {
    this.decisionDate = decisionDate;
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
    return "UnderwritingDecisionEntity[decisionId="
        + decisionId
        + ", policyNumber="
        + policyNumber
        + ", decisionDate="
        + decisionDate
        + "]";
  }
}
