package com.acme.insurance.pas.repository;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
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

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertPolicy(String policyNumber, String agentCode, String branchCode) {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, " +
                "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, " +
                "TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT, INCEPTION_DATE, RENEWAL_COUNT, RISK_SCORE) " +
                "VALUES (?, 'UMB', 'PN', '2025-07-01', '2026-07-01', 'C000000002', ?, ?, " +
                "NULL, NULL, NULL, NULL, NULL, NULL)",
                policyNumber, agentCode, branchCode);
    }

    private void insertCoverage(String policyNumber, int seq, String type, String description,
                                String territory, String classCode) {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.COVERAGES (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, " +
                "DESCRIPTION, EFFECTIVE_DATE, EXPIRY_DATE, RATING_TERRITORY, CLASS_CODE) " +
                "VALUES (?, ?, ?, ?, '2025-07-01', '2026-07-01', ?, ?)",
                policyNumber, seq, type, description, territory, classCode);
    }

    @Test
    public void findByPolicyNumber_mapsEverySeededColumn() {
        Policy policy = policyRepository.findByPolicyNumber("POL-00000002");

        assertThat(policy.getPolicyNumber(), is("POL-00000002"));
        assertThat(policy.getPolicyType(), is("AUT"));
        assertThat(policy.getPolicyStatus(), is("AC"));
        assertThat(policy.getEffectiveDate(), is((java.util.Date) Date.valueOf("2025-03-15")));
        assertThat(policy.getExpiryDate(), is((java.util.Date) Date.valueOf("2026-03-15")));
        assertThat(policy.getInceptionDate(), is((java.util.Date) Date.valueOf("2023-03-15")));
        assertThat(policy.getPolicyholderId(), is("C000000002"));
        assertThat(policy.getAgentCode(), is("AG2005"));
        assertThat(policy.getBranchCode(), is("CHI1"));
        assertThat(policy.getTotalPremium(), comparesEqualTo(new BigDecimal("890.50")));
        assertThat(policy.getDeductible(), comparesEqualTo(new BigDecimal("500.00")));
        assertThat(policy.getCoverageLimit(), comparesEqualTo(new BigDecimal("100000.00")));
        assertThat(policy.getRenewalCount(), is(2));
        assertThat(policy.getUwStatus(), is("AP"));
        assertThat(policy.getRiskScore(), is(42));
        assertThat(policy.getWebIndicator(), is("N"));
        assertThat(policy.getApiFlag(), is("Y"));
        assertThat(policy.getLastUpdated(), is(notNullValue()));
        assertThat(policy.getUpdatedBy(), is("TNGUYEN"));
    }

    @Test
    public void findByPolicyNumber_unknownPolicyReturnsNull() {
        assertThat(policyRepository.findByPolicyNumber("POL-99999999"), is(nullValue()));
        assertThat(policyRepository.findByPolicyNumber(null), is(nullValue()));
    }

    @Test
    public void findByPolicyNumber_trimsPaddedCharColumns() {
        insertPolicy("POL-T0000001", "AG1", "D1");

        Policy policy = policyRepository.findByPolicyNumber("POL-T0000001");

        assertThat(policy.getAgentCode(), is("AG1"));
        assertThat(policy.getBranchCode(), is("D1"));
        assertThat(policy.getUwStatus(), is("PN"));
        assertThat(policy.getWebIndicator(), is("N"));
        assertThat(policy.getApiFlag(), is("N"));
        assertThat(policy.getUpdatedBy(), is("SYSTEM"));
    }

    @Test
    public void findByPolicyNumber_nullableColumnsMapToNullOrZero() {
        insertPolicy("POL-T0000002", null, null);

        Policy policy = policyRepository.findByPolicyNumber("POL-T0000002");

        assertThat(policy.getPolicyType(), is("UMB"));
        assertThat(policy.getPolicyStatus(), is("PN"));
        assertThat(policy.getAgentCode(), is(nullValue()));
        assertThat(policy.getBranchCode(), is(nullValue()));
        assertThat(policy.getTotalPremium(), is(nullValue()));
        assertThat(policy.getDeductible(), is(nullValue()));
        assertThat(policy.getCoverageLimit(), is(nullValue()));
        assertThat(policy.getInceptionDate(), is(nullValue()));
        assertThat(policy.getRenewalCount(), is(0));
        assertThat(policy.getRiskScore(), is(0));
    }

    @Test
    public void findCoveragesByPolicyNumber_mapsSeededCoveragesInSequenceOrder() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000002");

        assertThat(coverages, hasSize(2));
        Coverage liability = coverages.get(0);
        assertThat(liability.getPolicyNumber(), is("POL-00000002"));
        assertThat(liability.getSequenceNum(), is(1));
        assertThat(liability.getCoverageType(), is("LIAB"));
        assertThat(liability.getDescription(), is("Liability"));
        assertThat(liability.getCoverageLimit(), comparesEqualTo(new BigDecimal("50000.00")));
        assertThat(liability.getDeductible(), comparesEqualTo(BigDecimal.ZERO));
        assertThat(liability.getPremium(), comparesEqualTo(new BigDecimal("450.50")));
        assertThat(liability.getEffectiveDate(), is((java.util.Date) Date.valueOf("2025-03-15")));
        assertThat(liability.getExpiryDate(), is((java.util.Date) Date.valueOf("2026-03-15")));
        assertThat(liability.getStatus(), is("AC"));
        assertThat(liability.getCoinsurancePct(), is(100));
        assertThat(liability.getRatingTerritory(), is("IL0601"));
        assertThat(liability.getClassCode(), is("A2010"));
        assertThat(coverages.get(1).getSequenceNum(), is(2));
        assertThat(coverages.get(1).getCoverageType(), is("COLL"));
    }

    @Test
    public void findCoveragesByPolicyNumber_ordersBySequenceNotInsertOrder() {
        insertPolicy("POL-T0000003", "AG1", "D1");
        insertCoverage("POL-T0000003", 3, "UMBR", "Umbrella", "IL0601", "U1");
        insertCoverage("POL-T0000003", 1, "LIAB", "Liability", "IL0601", "U1");
        insertCoverage("POL-T0000003", 2, "CYBR", "Cyber", "IL0601", "U1");

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-T0000003");

        assertThat(coverages, hasSize(3));
        assertThat(coverages.get(0).getCoverageType(), is("LIAB"));
        assertThat(coverages.get(1).getCoverageType(), is("CYBR"));
        assertThat(coverages.get(2).getCoverageType(), is("UMBR"));
    }

    @Test
    public void findCoveragesByPolicyNumber_nullableColumnsMapToNullAndDefaultsApply() {
        insertPolicy("POL-T0000004", "AG1", "D1");
        insertCoverage("POL-T0000004", 1, "PROP", null, null, null);

        Coverage coverage = policyRepository.findCoveragesByPolicyNumber("POL-T0000004").get(0);

        assertThat(coverage.getCoverageType(), is("PROP"));
        assertThat(coverage.getDescription(), is(nullValue()));
        assertThat(coverage.getRatingTerritory(), is(nullValue()));
        assertThat(coverage.getClassCode(), is(nullValue()));
        assertThat(coverage.getStatus(), is("AC"));
        assertThat(coverage.getCoinsurancePct(), is(100));
        assertThat(coverage.getPremium(), comparesEqualTo(BigDecimal.ZERO));
    }

    @Test
    public void findCoveragesByPolicyNumber_trimsPaddedCharColumns() {
        insertPolicy("POL-T0000005", "AG1", "D1");
        insertCoverage("POL-T0000005", 1, "PRO", "Padded  ", "IL1", "C1");

        Coverage coverage = policyRepository.findCoveragesByPolicyNumber("POL-T0000005").get(0);

        assertThat(coverage.getCoverageType(), is("PRO"));
        assertThat(coverage.getDescription(), is("Padded"));
        assertThat(coverage.getRatingTerritory(), is("IL1"));
        assertThat(coverage.getClassCode(), is("C1"));
    }

    @Test
    public void findCoveragesByPolicyNumber_emptyForUnknownOrUncoveredPolicy() {
        insertPolicy("POL-T0000006", "AG1", "D1");

        assertThat(policyRepository.findCoveragesByPolicyNumber("POL-T0000006"), is(empty()));
        assertThat(policyRepository.findCoveragesByPolicyNumber("POL-99999999"), is(empty()));
    }
}
