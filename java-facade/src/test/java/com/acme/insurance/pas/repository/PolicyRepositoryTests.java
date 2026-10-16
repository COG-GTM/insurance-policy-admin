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

    @Test
    public void findByPolicyNumber_mapsAllColumns() {
        Policy policy = policyRepository.findByPolicyNumber("POL-00000003");

        assertThat(policy, notNullValue());
        assertThat(policy.getPolicyNumber(), is("POL-00000003"));
        assertThat(policy.getPolicyType(), is("CGL"));
        assertThat(policy.getPolicyStatus(), is("AC"));
        assertThat(policy.getEffectiveDate().toString(), is("2025-06-01"));
        assertThat(policy.getExpiryDate().toString(), is("2026-06-01"));
        assertThat(policy.getPolicyholderId(), is("C000000003"));
        assertThat(policy.getAgentCode(), is("AG3010"));
        assertThat(policy.getBranchCode(), is("DET1"));
        assertThat(policy.getTotalPremium(), comparesEqualTo(new BigDecimal("4500.00")));
        assertThat(policy.getDeductible(), comparesEqualTo(new BigDecimal("2500.00")));
        assertThat(policy.getCoverageLimit(), comparesEqualTo(new BigDecimal("2000000.00")));
        assertThat(policy.getInceptionDate().toString(), is("2022-06-01"));
        assertThat(policy.getRenewalCount(), is(3));
        assertThat(policy.getUwStatus(), is("AP"));
        assertThat(policy.getRiskScore(), is(55));
        assertThat(policy.getWebIndicator(), is("Y"));
        assertThat(policy.getApiFlag(), is("Y"));
        assertThat(policy.getLastUpdated(), notNullValue());
        assertThat(policy.getUpdatedBy(), is("TNGUYEN"));
    }

    @Test
    public void findByPolicyNumber_unknownPolicy_returnsNull() {
        assertThat(policyRepository.findByPolicyNumber("POL-99999999"), nullValue());
    }

    @Test
    public void findByPolicyNumber_nullPolicyNumber_returnsNull() {
        assertThat(policyRepository.findByPolicyNumber(null), nullValue());
    }

    @Test
    public void findByPolicyNumber_isCaseSensitive() {
        assertThat(policyRepository.findByPolicyNumber("pol-00000001"), nullValue());
    }

    @Test
    public void findByPolicyNumber_trimsPaddedCharColumns() {
        insertHolder("C000000099");
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, "
                        + "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, "
                        + "UW_STATUS, WEB_INDICATOR, API_FLAG, UPDATED_BY) "
                        + "VALUES ('POL-099', 'HO ', 'PN', DATE '2025-01-01', DATE '2026-01-01', "
                        + "'C000000099', 'AG1  ', 'NY ', 'PN', 'N', 'N', 'BATCH   ')");

        Policy policy = policyRepository.findByPolicyNumber("POL-099");

        assertThat(policy, notNullValue());
        assertThat(policy.getPolicyNumber(), is("POL-099"));
        assertThat(policy.getPolicyType(), is("HO"));
        assertThat(policy.getAgentCode(), is("AG1"));
        assertThat(policy.getBranchCode(), is("NY"));
        assertThat(policy.getUpdatedBy(), is("BATCH"));
    }

    @Test
    public void findByPolicyNumber_nullOptionalColumns_mappedAsNullOrZero() {
        insertHolder("C000000098");
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, "
                        + "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, "
                        + "TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT, INCEPTION_DATE, "
                        + "RENEWAL_COUNT, RISK_SCORE, UPDATED_BY) "
                        + "VALUES ('POL-00000098', 'AUT', 'PN', DATE '2025-02-01', DATE '2026-02-01', "
                        + "'C000000098', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'SYSTEM')");

        Policy policy = policyRepository.findByPolicyNumber("POL-00000098");

        assertThat(policy, notNullValue());
        assertThat(policy.getAgentCode(), nullValue());
        assertThat(policy.getBranchCode(), nullValue());
        assertThat(policy.getTotalPremium(), nullValue());
        assertThat(policy.getDeductible(), nullValue());
        assertThat(policy.getCoverageLimit(), nullValue());
        assertThat(policy.getInceptionDate(), nullValue());
        assertThat(policy.getRenewalCount(), is(0));
        assertThat(policy.getRiskScore(), is(0));
        assertThat(policy.getUwStatus(), is("PN"));
        assertThat(policy.getWebIndicator(), is("N"));
        assertThat(policy.getApiFlag(), is("N"));
    }

    @Test
    public void findCoveragesByPolicyNumber_mapsAllColumns() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000001");

        assertThat(coverages, hasSize(2));
        Coverage dwelling = coverages.get(0);
        assertThat(dwelling.getPolicyNumber(), is("POL-00000001"));
        assertThat(dwelling.getSequenceNum(), is(1));
        assertThat(dwelling.getCoverageType(), is("DWEL"));
        assertThat(dwelling.getDescription(), is("Dwelling Coverage"));
        assertThat(dwelling.getCoverageLimit(), comparesEqualTo(new BigDecimal("400000.00")));
        assertThat(dwelling.getDeductible(), comparesEqualTo(new BigDecimal("1000.00")));
        assertThat(dwelling.getPremium(), comparesEqualTo(new BigDecimal("850.00")));
        assertThat(dwelling.getEffectiveDate().toString(), is("2025-01-01"));
        assertThat(dwelling.getExpiryDate().toString(), is("2026-01-01"));
        assertThat(dwelling.getStatus(), is("AC"));
        assertThat(dwelling.getCoinsurancePct(), is(80));
        assertThat(dwelling.getRatingTerritory(), is("IL0627"));
        assertThat(dwelling.getClassCode(), is("R1001"));
    }

    @Test
    public void findCoveragesByPolicyNumber_orderedBySequenceNumber() {
        insertCoverage("POL-00000003", 5, "UMBR", "Umbrella");
        insertCoverage("POL-00000003", 3, "AUTO", "Hired Auto");

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000003");

        assertThat(coverages, hasSize(4));
        assertThat(coverages.get(0).getSequenceNum(), is(1));
        assertThat(coverages.get(1).getSequenceNum(), is(2));
        assertThat(coverages.get(2).getSequenceNum(), is(3));
        assertThat(coverages.get(2).getCoverageType(), is("AUTO"));
        assertThat(coverages.get(3).getSequenceNum(), is(5));
        assertThat(coverages.get(3).getCoverageType(), is("UMBR"));
    }

    @Test
    public void findCoveragesByPolicyNumber_onlyReturnsCoveragesForRequestedPolicy() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000002");

        assertThat(coverages, hasSize(2));
        for (Coverage coverage : coverages) {
            assertThat(coverage.getPolicyNumber(), is("POL-00000002"));
        }
        assertThat(coverages.get(0).getCoverageType(), is("LIAB"));
        assertThat(coverages.get(0).getDeductible(), comparesEqualTo(BigDecimal.ZERO));
        assertThat(coverages.get(1).getCoverageType(), is("COLL"));
    }

    @Test
    public void findCoveragesByPolicyNumber_unknownPolicy_returnsEmptyList() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-99999999");

        assertThat(coverages, notNullValue());
        assertThat(coverages, is(empty()));
    }

    @Test
    public void findCoveragesByPolicyNumber_nullOptionalColumns_mappedAsNull() {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.COVERAGES (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, "
                        + "DESCRIPTION, COVERAGE_LIMIT, DEDUCTIBLE, PREMIUM, EFFECTIVE_DATE, "
                        + "EXPIRY_DATE, STATUS, COINSURANCE_PCT, RATING_TERRITORY, CLASS_CODE) "
                        + "VALUES ('POL-00000002', 9, 'MED', NULL, NULL, NULL, NULL, "
                        + "DATE '2025-03-15', DATE '2026-03-15', 'CN', NULL, NULL, NULL)");

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000002");

        Coverage medical = coverages.get(coverages.size() - 1);
        assertThat(medical.getSequenceNum(), is(9));
        assertThat(medical.getCoverageType(), is("MED"));
        assertThat(medical.getStatus(), is("CN"));
        assertThat(medical.getDescription(), nullValue());
        assertThat(medical.getCoverageLimit(), nullValue());
        assertThat(medical.getDeductible(), nullValue());
        assertThat(medical.getPremium(), nullValue());
        assertThat(medical.getCoinsurancePct(), is(0));
        assertThat(medical.getRatingTerritory(), nullValue());
        assertThat(medical.getClassCode(), nullValue());
    }

    @Test
    public void findCoveragesByPolicyNumber_trimsPaddedCharColumns() {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.COVERAGES (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, "
                        + "DESCRIPTION, EFFECTIVE_DATE, EXPIRY_DATE, STATUS, RATING_TERRITORY, CLASS_CODE) "
                        + "VALUES ('POL-00000002', 8, 'GL  ', '  Glass Breakage  ', "
                        + "DATE '2025-03-15', DATE '2026-03-15', 'A ', 'IL1  ', 'G1   ')");

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000002");

        Coverage glass = coverages.get(coverages.size() - 1);
        assertThat(glass.getSequenceNum(), is(8));
        assertThat(glass.getCoverageType(), is("GL"));
        assertThat(glass.getDescription(), is("Glass Breakage"));
        assertThat(glass.getStatus(), is("A"));
        assertThat(glass.getRatingTerritory(), is("IL1"));
        assertThat(glass.getClassCode(), is("G1"));
        assertThat(glass.getCoinsurancePct(), is(100));
    }

    private void insertHolder(String custId) {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICY_HOLDERS (CUST_ID, LAST_NAME) VALUES (?, 'Test')",
                custId);
    }

    private void insertCoverage(String policyNumber, int seq, String type, String description) {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.COVERAGES (POLICY_NUMBER, SEQUENCE_NUM, COVERAGE_TYPE, "
                        + "DESCRIPTION, EFFECTIVE_DATE, EXPIRY_DATE) VALUES (?, ?, ?, ?, ?, ?)",
                policyNumber, seq, type, description,
                Date.valueOf("2025-06-01"), Date.valueOf("2026-06-01"));
    }
}
