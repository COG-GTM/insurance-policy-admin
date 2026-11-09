package com.acme.insurance.pas.underwriting.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/**
 * Host variables of the SELECT in 2000-READ-POLICY-DATA. Values are kept nullable so the
 * service can reproduce DB2 SQLCODE -305 (NULL fetched without an indicator variable).
 */
public record PolicyCustomerRow(
        String policyNumber,
        String policyType,
        BigDecimal totalPremium,
        BigDecimal coverageLimit,
        String branchCode,
        String custType,
        Integer creditScore,
        String riskTier) {

    public boolean hasNullColumn() {
        return policyNumber == null || policyType == null || totalPremium == null || coverageLimit == null
                || branchCode == null || custType == null || creditScore == null || riskTier == null;
    }

    public static class Mapper implements RowMapper<PolicyCustomerRow> {
        @Override
        public PolicyCustomerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PolicyCustomerRow(
                    rs.getString("POLICY_NUMBER"),
                    rs.getString("POLICY_TYPE"),
                    rs.getBigDecimal("TOTAL_PREMIUM"),
                    rs.getBigDecimal("COVERAGE_LIMIT"),
                    rs.getString("BRANCH_CODE"),
                    rs.getString("CUST_TYPE"),
                    rs.getObject("CREDIT_SCORE", Integer.class),
                    rs.getString("RISK_TIER"));
        }
    }
}
