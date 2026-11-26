package com.acme.insurance.pas.repository;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Policy Repository - Direct JDBC access to DB2 on z/OS.
 *
 * Uses JdbcTemplate (no JPA/Hibernate - DB2 z/OS driver has
 * compatibility issues with Hibernate dialect). Queries are
 * read-only; all writes go through CICS programs.
 *
 * NOTE: Column names match the DB2 DDL exactly. The mainframe
 * DBA team uses uppercase column names (COBOL convention).
 *
 * @author T. Nguyen (2022)
 */
@Repository
public class PolicyRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String FIND_POLICY_SQL =
            "SELECT POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, " +
            "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, " +
            "AGENT_CODE, BRANCH_CODE, TOTAL_PREMIUM, DEDUCTIBLE, " +
            "COVERAGE_LIMIT, INCEPTION_DATE, RENEWAL_COUNT, " +
            "UW_STATUS, RISK_SCORE, WEB_INDICATOR, API_FLAG, " +
            "LAST_UPDATED, UPDATED_BY " +
            "FROM ACMEINS.POLICIES " +
            "WHERE POLICY_NUMBER = ?";

    private static final String FIND_COVERAGES_SQL =
            "SELECT POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, " +
            "DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE, PREMIUM, " +
            "EFFECTIVE_DATE, EXPIRY_DATE, STATUS, " +
            "COINSURANCE_PCT, RATING_TERRITORY, CLASS_CODE " +
            "FROM ACMEINS.COVERAGES " +
            "WHERE POLICY_NUMBER = ? " +
            "ORDER BY SEQUENCE_NUM";

    private static final String FIND_POLICY_COVERAGES_SQL =
            "SELECT P.POLICY_NUMBER, P.POLICY_TYPE, P.POLICY_STATUS, " +
            "P.EFFECTIVE_DATE, P.EXPIRY_DATE, P.POLICYHOLDER_ID, " +
            "P.AGENT_CODE, P.BRANCH_CODE, P.TOTAL_PREMIUM, P.DEDUCTIBLE, " +
            "P.COVERAGE_LIMIT, P.INCEPTION_DATE, P.RENEWAL_COUNT, " +
            "P.UW_STATUS, P.RISK_SCORE, P.WEB_INDICATOR, P.API_FLAG, " +
            "P.LAST_UPDATED, P.UPDATED_BY, " +
            "C.POLICY_NUMBER AS C_POLICY_NUMBER, C.SEQUENCE_NUM AS C_SEQUENCE_NUM, " +
            "C.COVERAGE_TYPE AS C_COVERAGE_TYPE, C.DESCRIPTION AS C_DESCRIPTION, " +
            "C.COVERAGE_LIMIT AS C_COVERAGE_LIMIT, C.DEDUCTIBLE AS C_DEDUCTIBLE, " +
            "C.PREMIUM AS C_PREMIUM, C.EFFECTIVE_DATE AS C_EFFECTIVE_DATE, " +
            "C.EXPIRY_DATE AS C_EXPIRY_DATE, C.STATUS AS C_STATUS, " +
            "C.COINSURANCE_PCT AS C_COINSURANCE_PCT, " +
            "C.RATING_TERRITORY AS C_RATING_TERRITORY, C.CLASS_CODE AS C_CLASS_CODE " +
            "FROM ACMEINS.POLICIES P " +
            "LEFT JOIN ACMEINS.COVERAGES C ON C.POLICY_NUMBER = P.POLICY_NUMBER " +
            "WHERE P.POLICY_NUMBER = ? " +
            "ORDER BY C.SEQUENCE_NUM";

    public Policy findByPolicyNumber(String policyNumber) {
        List<Policy> results = jdbcTemplate.query(
                FIND_POLICY_SQL,
                new Object[]{policyNumber},
                new PolicyRowMapper());
        if (results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    public List<Coverage> findCoveragesByPolicyNumber(String policyNumber) {
        return jdbcTemplate.query(
                FIND_COVERAGES_SQL,
                new Object[]{policyNumber},
                new CoverageRowMapper(""));
    }

    /**
     * Coverages of a policy, or null when the policy does not exist,
     * in one round trip to DB2 instead of a policy lookup plus a
     * coverage query.
     */
    public List<Coverage> findCoveragesIfPolicyExists(String policyNumber) {
        return jdbcTemplate.query(
                FIND_POLICY_COVERAGES_SQL,
                new Object[]{policyNumber},
                new ResultSetExtractor<List<Coverage>>() {
                    @Override
                    public List<Coverage> extractData(ResultSet rs) throws SQLException {
                        CoverageRowMapper coverageMapper = new CoverageRowMapper("C_");
                        List<Coverage> coverages = null;
                        int rowNum = 0;
                        while (rs.next()) {
                            if (coverages == null) {
                                new PolicyRowMapper().mapRow(rs, 0);
                                coverages = new ArrayList<Coverage>();
                            }
                            if (rs.getString("C_POLICY_NUMBER") != null) {
                                coverages.add(coverageMapper.mapRow(rs, rowNum++));
                            }
                        }
                        return coverages;
                    }
                });
    }

    private static class PolicyRowMapper implements RowMapper<Policy> {
        @Override
        public Policy mapRow(ResultSet rs, int rowNum) throws SQLException {
            Policy policy = new Policy();
            policy.setPolicyNumber(rs.getString("POLICY_NUMBER").trim());
            policy.setPolicyType(rs.getString("POLICY_TYPE").trim());
            policy.setPolicyStatus(rs.getString("POLICY_STATUS").trim());
            policy.setEffectiveDate(rs.getDate("EFFECTIVE_DATE"));
            policy.setExpiryDate(rs.getDate("EXPIRY_DATE"));
            policy.setPolicyholderId(rs.getString("POLICYHOLDER_ID").trim());
            policy.setAgentCode(rs.getString("AGENT_CODE") != null ?
                    rs.getString("AGENT_CODE").trim() : null);
            policy.setBranchCode(rs.getString("BRANCH_CODE") != null ?
                    rs.getString("BRANCH_CODE").trim() : null);
            policy.setTotalPremium(rs.getBigDecimal("TOTAL_PREMIUM"));
            policy.setDeductible(rs.getBigDecimal("DEDUCTIBLE"));
            policy.setCoverageLimit(rs.getBigDecimal("COVERAGE_LIMIT"));
            policy.setInceptionDate(rs.getDate("INCEPTION_DATE"));
            policy.setRenewalCount(rs.getInt("RENEWAL_COUNT"));
            policy.setUwStatus(rs.getString("UW_STATUS").trim());
            policy.setRiskScore(rs.getInt("RISK_SCORE"));
            policy.setWebIndicator(rs.getString("WEB_INDICATOR").trim());
            policy.setApiFlag(rs.getString("API_FLAG").trim());
            policy.setLastUpdated(rs.getTimestamp("LAST_UPDATED"));
            policy.setUpdatedBy(rs.getString("UPDATED_BY").trim());
            return policy;
        }
    }

    private static class CoverageRowMapper implements RowMapper<Coverage> {
        private final String prefix;

        CoverageRowMapper(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Coverage mapRow(ResultSet rs, int rowNum) throws SQLException {
            Coverage coverage = new Coverage();
            coverage.setPolicyNumber(rs.getString(prefix + "POLICY_NUMBER").trim());
            coverage.setSequenceNum(rs.getInt(prefix + "SEQUENCE_NUM"));
            coverage.setCoverageType(rs.getString(prefix + "COVERAGE_TYPE").trim());
            coverage.setDescription(rs.getString(prefix + "DESCRIPTION") != null ?
                    rs.getString(prefix + "DESCRIPTION").trim() : null);
            coverage.setCoverageLimit(rs.getBigDecimal(prefix + "COVERAGE_LIMIT"));
            coverage.setDeductible(rs.getBigDecimal(prefix + "DEDUCTIBLE"));
            coverage.setPremium(rs.getBigDecimal(prefix + "PREMIUM"));
            coverage.setEffectiveDate(rs.getDate(prefix + "EFFECTIVE_DATE"));
            coverage.setExpiryDate(rs.getDate(prefix + "EXPIRY_DATE"));
            coverage.setStatus(rs.getString(prefix + "STATUS").trim());
            coverage.setCoinsurancePct(rs.getInt(prefix + "COINSURANCE_PCT"));
            coverage.setRatingTerritory(rs.getString(prefix + "RATING_TERRITORY") != null ?
                    rs.getString(prefix + "RATING_TERRITORY").trim() : null);
            coverage.setClassCode(rs.getString(prefix + "CLASS_CODE") != null ?
                    rs.getString(prefix + "CLASS_CODE").trim() : null);
            return coverage;
        }
    }
}
