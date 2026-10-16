package com.acme.insurance.pas.dto;

import com.acme.insurance.pas.model.Policy;

import java.math.BigDecimal;
import java.util.Date;

/**
 * Public API representation of a policy.
 *
 * Exposes only client-facing fields. Internal underwriting/audit
 * fields on {@link Policy} (uwStatus, riskScore, updatedBy,
 * agentCode, webIndicator, apiFlag) are intentionally not exposed.
 *
 * NOTE: Using old-style Java bean pattern (no Lombok, no records)
 * for compatibility with Java 8 and Spring Boot 1.5.
 *
 * @author T. Nguyen (2022)
 */
public class PolicyResponse {

    private String policyNumber;
    private String policyType;
    private String policyStatus;
    private Date effectiveDate;
    private Date expiryDate;
    private String policyholderId;
    private String branchCode;
    private BigDecimal totalPremium;
    private BigDecimal deductible;
    private BigDecimal coverageLimit;
    private Date inceptionDate;
    private int renewalCount;
    private Date lastUpdated;

    public static PolicyResponse from(Policy p) {
        PolicyResponse r = new PolicyResponse();
        r.policyNumber = p.getPolicyNumber();
        r.policyType = p.getPolicyType();
        r.policyStatus = p.getPolicyStatus();
        r.effectiveDate = p.getEffectiveDate();
        r.expiryDate = p.getExpiryDate();
        r.policyholderId = p.getPolicyholderId();
        r.branchCode = p.getBranchCode();
        r.totalPremium = p.getTotalPremium();
        r.deductible = p.getDeductible();
        r.coverageLimit = p.getCoverageLimit();
        r.inceptionDate = p.getInceptionDate();
        r.renewalCount = p.getRenewalCount();
        r.lastUpdated = p.getLastUpdated();
        return r;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getPolicyType() {
        return policyType;
    }

    public String getPolicyStatus() {
        return policyStatus;
    }

    public Date getEffectiveDate() {
        return effectiveDate;
    }

    public Date getExpiryDate() {
        return expiryDate;
    }

    public String getPolicyholderId() {
        return policyholderId;
    }

    public String getBranchCode() {
        return branchCode;
    }

    public BigDecimal getTotalPremium() {
        return totalPremium;
    }

    public BigDecimal getDeductible() {
        return deductible;
    }

    public BigDecimal getCoverageLimit() {
        return coverageLimit;
    }

    public Date getInceptionDate() {
        return inceptionDate;
    }

    public int getRenewalCount() {
        return renewalCount;
    }

    public Date getLastUpdated() {
        return lastUpdated;
    }
}
