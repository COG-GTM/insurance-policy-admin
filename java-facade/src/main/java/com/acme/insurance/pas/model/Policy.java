package com.acme.insurance.pas.model;

import java.math.BigDecimal;
import java.util.Date;

/**
 * Policy domain model.
 * Maps to ACMEINS.POLICIES DB2 table on the mainframe.
 *
 * NOTE: Using old-style Java bean pattern (no Lombok, no records)
 * for compatibility with Java 8.
 *
 * @author T. Nguyen (2022)
 */
public class Policy {

    private String policyNumber;
    private String policyType;
    private String policyStatus;
    private Date effectiveDate;
    private Date expiryDate;
    private String policyholderId;
    private String agentCode;
    private String branchCode;
    private BigDecimal totalPremium;
    private BigDecimal deductible;
    private BigDecimal coverageLimit;
    private Date inceptionDate;
    private int renewalCount;
    private String uwStatus;
    private int riskScore;
    private String webIndicator;
    private String apiFlag;
    private Date lastUpdated;
    private String updatedBy;

    public Policy() {
    }

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

    public Date getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(Date effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public Date getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(Date expiryDate) {
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

    public Date getInceptionDate() {
        return inceptionDate;
    }

    public void setInceptionDate(Date inceptionDate) {
        this.inceptionDate = inceptionDate;
    }

    public int getRenewalCount() {
        return renewalCount;
    }

    public void setRenewalCount(int renewalCount) {
        this.renewalCount = renewalCount;
    }

    public String getUwStatus() {
        return uwStatus;
    }

    public void setUwStatus(String uwStatus) {
        this.uwStatus = uwStatus;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(int riskScore) {
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

    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
