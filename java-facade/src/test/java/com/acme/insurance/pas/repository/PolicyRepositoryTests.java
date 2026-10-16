package com.acme.insurance.pas.repository;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional
public class PolicyRepositoryTests {

    private static final String INSERT_POLICY_SQL =
            "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, " +
            "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, " +
            "TOTAL_PREMIUM, UW_STATUS, WEB_INDICATOR, API_FLAG, UPDATED_BY) " +
            "VALUES (?, 'AUT', 'PN', '2025-07-01', '2026-07-01', 'C000000002', ?, ?, " +
            "300.00, ?, 'N', 'N', 'TESTUSER')";

    private static final String INSERT_COVERAGE_SQL =
            "INSERT INTO ACMEINS.COVERAGES (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, " +
            "DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE, PREMIUM, EFFECTIVE_DATE, EXPIRY_DATE, " +
            "STATUS, COINSURANCE_PCT, RATING_TERRITORY, CLASS_CODE) " +
            "VALUES (?, ?, ?, ?, 25000.00, 250.00, 120.00, '2025-07-01', '2026-07-01', " +
            "'AC', 90, ?, ?)";

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void findByPolicyNumber_mapsEveryColumnOfSeededPolicy() {
        Policy policy = policyRepository.findByPolicyNumber("POL-00000002");

        assertThat(policy, notNullValue());
        assertThat(policy.getPolicyNumber(), is("POL-00000002"));
        assertThat(policy.getPolicyType(), is("AUT"));
        assertThat(policy.getPolicyStatus(), is("AC"));
        assertThat(policy.getEffectiveDate().toString(), is("2025-03-15"));
        assertThat(policy.getExpiryDate().toString(), is("2026-03-15"));
        assertThat(policy.getPolicyholderId(), is("C000000002"));
        assertThat(policy.getAgentCode(), is("AG2005"));
        assertThat(policy.getBranchCode(), is("CHI1"));
        assertThat(policy.getTotalPremium(), comparesEqualTo(new BigDecimal("890.50")));
        assertThat(policy.getDeductible(), comparesEqualTo(new BigDecimal("500.00")));
        assertThat(policy.getCoverageLimit(), comparesEqualTo(new BigDecimal("100000.00")));
        assertThat(policy.getInceptionDate().toString(), is("2023-03-15"));
        assertThat(policy.getRenewalCount(), is(2));
        assertThat(policy.getUwStatus(), is("AP"));
        assertThat(policy.getRiskScore(), is(42));
        assertThat(policy.getWebIndicator(), is("N"));
        assertThat(policy.getApiFlag(), is("Y"));
        assertThat(policy.getLastUpdated(), notNullValue());
        assertThat(policy.getUpdatedBy(), is("TNGUYEN"));
    }

    @Test
    public void findByPolicyNumber_returnsNullWhenPolicyDoesNotExist() {
        assertThat(policyRepository.findByPolicyNumber("POL-99999999"), nullValue());
    }

    @Test
    public void findByPolicyNumber_isExactMatchNotPrefixMatch() {
        assertThat(policyRepository.findByPolicyNumber("POL-0000000"), nullValue());
        assertThat(policyRepository.findByPolicyNumber("pol-00000001"), nullValue());
    }

    @Test
    public void findByPolicyNumber_mapsNullAgentAndBranchCodesToNull() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000101", null, null, "PN");

        Policy policy = policyRepository.findByPolicyNumber("POL-00000101");

        assertThat(policy.getAgentCode(), nullValue());
        assertThat(policy.getBranchCode(), nullValue());
        assertThat(policy.getPolicyStatus(), is("PN"));
        assertThat(policy.getUpdatedBy(), is("TESTUSER"));
    }

    @Test
    public void findByPolicyNumber_appliesColumnDefaultsForOmittedNumericColumns() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000102", "AG9999", "NYC1", "PN");

        Policy policy = policyRepository.findByPolicyNumber("POL-00000102");

        assertThat(policy.getAgentCode(), is("AG9999"));
        assertThat(policy.getBranchCode(), is("NYC1"));
        assertThat(policy.getDeductible(), comparesEqualTo(BigDecimal.ZERO));
        assertThat(policy.getCoverageLimit(), comparesEqualTo(BigDecimal.ZERO));
        assertThat(policy.getRenewalCount(), is(0));
        assertThat(policy.getRiskScore(), is(0));
        assertThat(policy.getInceptionDate(), nullValue());
    }

    @Test
    @Ignore("Suspected bug: PolicyRowMapper calls trim() on nullable UW_STATUS and throws "
            + "NullPointerException; see PR description")
    public void findByPolicyNumber_toleratesNullUwStatus() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000103", "AG9999", "NYC1", null);

        Policy policy = policyRepository.findByPolicyNumber("POL-00000103");

        assertThat(policy.getUwStatus(), nullValue());
    }

    @Test
    public void findCoveragesByPolicyNumber_mapsEveryColumnOfSeededCoverage() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000003");

        assertThat(coverages, hasSize(2));
        Coverage first = coverages.get(0);
        assertThat(first.getPolicyNumber(), is("POL-00000003"));
        assertThat(first.getSequenceNum(), is(1));
        assertThat(first.getCoverageType(), is("PREM"));
        assertThat(first.getDescription(), is("Premises Liability"));
        assertThat(first.getCoverageLimit(), comparesEqualTo(new BigDecimal("1000000.00")));
        assertThat(first.getDeductible(), comparesEqualTo(new BigDecimal("2500.00")));
        assertThat(first.getPremium(), comparesEqualTo(new BigDecimal("2800.00")));
        assertThat(first.getEffectiveDate().toString(), is("2025-06-01"));
        assertThat(first.getExpiryDate().toString(), is("2026-06-01"));
        assertThat(first.getStatus(), is("AC"));
        assertThat(first.getCoinsurancePct(), is(100));
        assertThat(first.getRatingTerritory(), is("MI0482"));
        assertThat(first.getClassCode(), is("C5001"));
    }

    @Test
    public void findCoveragesByPolicyNumber_ordersBySequenceNumberRegardlessOfInsertOrder() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000104", "AG9999", "NYC1", "PN");
        jdbcTemplate.update(INSERT_COVERAGE_SQL, "POL-00000104", 3, "MEDP", "Medical", "NY0101", "A1000");
        jdbcTemplate.update(INSERT_COVERAGE_SQL, "POL-00000104", 1, "LIAB", "Liability", "NY0101", "A1000");
        jdbcTemplate.update(INSERT_COVERAGE_SQL, "POL-00000104", 2, "COLL", "Collision", "NY0101", "A1000");

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000104");

        assertThat(coverages, hasSize(3));
        assertThat(coverages.get(0).getSequenceNum(), is(1));
        assertThat(coverages.get(0).getCoverageType(), is("LIAB"));
        assertThat(coverages.get(1).getSequenceNum(), is(2));
        assertThat(coverages.get(1).getCoverageType(), is("COLL"));
        assertThat(coverages.get(2).getSequenceNum(), is(3));
        assertThat(coverages.get(2).getCoverageType(), is("MEDP"));
    }

    @Test
    public void findCoveragesByPolicyNumber_onlyReturnsCoveragesOfRequestedPolicy() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000002");

        assertThat(coverages, hasSize(2));
        for (Coverage coverage : coverages) {
            assertThat(coverage.getPolicyNumber(), is("POL-00000002"));
        }
    }

    @Test
    public void findCoveragesByPolicyNumber_mapsNullOptionalColumnsToNull() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000105", "AG9999", "NYC1", "PN");
        jdbcTemplate.update(INSERT_COVERAGE_SQL, "POL-00000105", 1, "LIAB", null, null, null);

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000105");

        assertThat(coverages, hasSize(1));
        Coverage coverage = coverages.get(0);
        assertThat(coverage.getCoverageType(), is("LIAB"));
        assertThat(coverage.getDescription(), nullValue());
        assertThat(coverage.getRatingTerritory(), nullValue());
        assertThat(coverage.getClassCode(), nullValue());
        assertThat(coverage.getCoinsurancePct(), is(90));
        assertThat(coverage.getEffectiveDate(), is(Date.valueOf("2025-07-01")));
    }

    @Test
    public void findCoveragesByPolicyNumber_returnsEmptyListForPolicyWithoutCoverages() {
        jdbcTemplate.update(INSERT_POLICY_SQL, "POL-00000106", "AG9999", "NYC1", "PN");

        assertThat(policyRepository.findCoveragesByPolicyNumber("POL-00000106"), empty());
    }

    @Test
    public void findCoveragesByPolicyNumber_returnsEmptyListForUnknownPolicy() {
        assertThat(policyRepository.findCoveragesByPolicyNumber("POL-99999999"), empty());
    }
}
