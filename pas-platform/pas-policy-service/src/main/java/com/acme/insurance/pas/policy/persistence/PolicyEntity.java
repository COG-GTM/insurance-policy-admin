package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** pas.policies (ACMEINS.POLICIES, POLICY-RECORD.cpy). */
@Entity
@Table(name = "policies")
public class PolicyEntity {

  @Id
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_number", length = 12, nullable = false)
  private String policyNumber;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_type", length = 3, nullable = false)
  private String policyType;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policy_status", length = 2, nullable = false)
  private String policyStatus;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDate expiryDate;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "policyholder_id", length = 10, nullable = false)
  private String policyholderId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "agent_code", length = 6)
  private String agentCode;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "branch_code", length = 4)
  private String branchCode;

  @Column(name = "total_premium", precision = 11, scale = 2)
  private BigDecimal totalPremium;

  @Column(name = "deductible", precision = 9, scale = 2)
  private BigDecimal deductible;

  @Column(name = "coverage_limit", precision = 13, scale = 2)
  private BigDecimal coverageLimit;

  @Column(name = "inception_date")
  private LocalDate inceptionDate;

  @Column(name = "renewal_count")
  private Short renewalCount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "uw_status", length = 2)
  private String uwStatus;

  @Column(name = "risk_score")
  private Short riskScore;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "web_indicator", length = 1)
  private String webIndicator;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "api_flag", length = 1)
  private String apiFlag;

  @Column(name = "last_updated", nullable = false)
  private LocalDateTime lastUpdated;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "updated_by", length = 8, nullable = false)
  private String updatedBy;

  public String getPolicyNumber() {
    return policyNumber;
  }

  public void setPolicyNumber(String policyNumber) {
    this.policyNumber = policyNumber;
  }

  public String getPolicyType() {
    return policyType;
  }

  public void setPolicyType(String policyType) {
    this.policyType = policyType;
  }

  public String getPolicyStatus() {
    return policyStatus;
  }

  public void setPolicyStatus(String policyStatus) {
    this.policyStatus = policyStatus;
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

  public String getPolicyholderId() {
    return policyholderId;
  }

  public void setPolicyholderId(String policyholderId) {
    this.policyholderId = policyholderId;
  }

  public String getAgentCode() {
    return agentCode;
  }

  public void setAgentCode(String agentCode) {
    this.agentCode = agentCode;
  }

  public String getBranchCode() {
    return branchCode;
  }

  public void setBranchCode(String branchCode) {
    this.branchCode = branchCode;
  }

  public BigDecimal getTotalPremium() {
    return totalPremium;
  }

  public void setTotalPremium(BigDecimal totalPremium) {
    this.totalPremium = totalPremium;
  }

  public BigDecimal getDeductible() {
    return deductible;
  }

  public void setDeductible(BigDecimal deductible) {
    this.deductible = deductible;
  }

  public BigDecimal getCoverageLimit() {
    return coverageLimit;
  }

  public void setCoverageLimit(BigDecimal coverageLimit) {
    this.coverageLimit = coverageLimit;
  }

  public LocalDate getInceptionDate() {
    return inceptionDate;
  }

  public void setInceptionDate(LocalDate inceptionDate) {
    this.inceptionDate = inceptionDate;
  }

  public Short getRenewalCount() {
    return renewalCount;
  }

  public void setRenewalCount(Short renewalCount) {
    this.renewalCount = renewalCount;
  }

  public String getUwStatus() {
    return uwStatus;
  }

  public void setUwStatus(String uwStatus) {
    this.uwStatus = uwStatus;
  }

  public Short getRiskScore() {
    return riskScore;
  }

  public void setRiskScore(Short riskScore) {
    this.riskScore = riskScore;
  }

  public String getWebIndicator() {
    return webIndicator;
  }

  public void setWebIndicator(String webIndicator) {
    this.webIndicator = webIndicator;
  }

  public String getApiFlag() {
    return apiFlag;
  }

  public void setApiFlag(String apiFlag) {
    this.apiFlag = apiFlag;
  }

  public LocalDateTime getLastUpdated() {
    return lastUpdated;
  }

  public void setLastUpdated(LocalDateTime lastUpdated) {
    this.lastUpdated = lastUpdated;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  @Override
  public String toString() {
    return "PolicyEntity[policyNumber=" + policyNumber + "]";
  }
}
