package com.acme.insurance.pas.repository;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DB2 row mapping in {@link PolicyRepository}. DB2 CHAR
 * columns are blank-padded, so the mappers must trim every string they read.
 */
public class PolicyRepositoryRowMapperTests {

    private JdbcTemplate jdbcTemplate;
    private PolicyRepository policyRepository;

    @Before
    public void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        policyRepository = new PolicyRepository();
        ReflectionTestUtils.setField(policyRepository, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    public void findByPolicyNumber_trimsBlankPaddedCharColumns() throws Exception {
        ResultSet rs = paddedPolicyRow();
        stubQueryWithRows(rs);

        Policy policy = policyRepository.findByPolicyNumber("POL-00000001");

        assertThat(policy.getPolicyNumber(), is("POL-00000001"));
        assertThat(policy.getPolicyType(), is("HOM"));
        assertThat(policy.getPolicyStatus(), is("AC"));
        assertThat(policy.getPolicyholderId(), is("C000000001"));
        assertThat(policy.getAgentCode(), is("AG1001"));
        assertThat(policy.getBranchCode(), is("CHI1"));
        assertThat(policy.getUwStatus(), is("AP"));
        assertThat(policy.getWebIndicator(), is("Y"));
        assertThat(policy.getApiFlag(), is("N"));
        assertThat(policy.getUpdatedBy(), is("TNGUYEN"));
    }

    @Test
    public void findByPolicyNumber_copiesNumericAndDateColumnsUnchanged() throws Exception {
        ResultSet rs = paddedPolicyRow();
        stubQueryWithRows(rs);

        Policy policy = policyRepository.findByPolicyNumber("POL-00000001");

        assertThat(policy.getTotalPremium(), is(new BigDecimal("1250.00")));
        assertThat(policy.getDeductible(), is(new BigDecimal("1000.00")));
        assertThat(policy.getCoverageLimit(), is(new BigDecimal("500000.00")));
        assertThat(policy.getEffectiveDate(), is((java.util.Date) Date.valueOf("2025-01-01")));
        assertThat(policy.getExpiryDate(), is((java.util.Date) Date.valueOf("2026-01-01")));
        assertThat(policy.getInceptionDate(), is((java.util.Date) Date.valueOf("2020-01-01")));
        assertThat(policy.getLastUpdated(),
                is((java.util.Date) Timestamp.valueOf("2025-01-02 03:04:05")));
        assertThat(policy.getRenewalCount(), is(5));
        assertThat(policy.getRiskScore(), is(35));
    }

    @Test
    public void findByPolicyNumber_leavesNullAgentAndBranchCodesNull() throws Exception {
        ResultSet rs = paddedPolicyRow();
        when(rs.getString("AGENT_CODE")).thenReturn(null);
        when(rs.getString("BRANCH_CODE")).thenReturn(null);
        stubQueryWithRows(rs);

        Policy policy = policyRepository.findByPolicyNumber("POL-00000001");

        assertThat(policy.getAgentCode(), nullValue());
        assertThat(policy.getBranchCode(), nullValue());
        assertThat(policy.getPolicyNumber(), is("POL-00000001"));
    }

    @Test
    public void findByPolicyNumber_passesPolicyNumberAsBindParameter() throws Exception {
        stubQueryWithRows();

        policyRepository.findByPolicyNumber("POL-00000042");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue(), containsString("FROM ACMEINS.POLICIES"));
        assertThat(sql.getValue(), containsString("WHERE POLICY_NUMBER = ?"));
        assertThat(sql.getValue(), not(containsString("POL-00000042")));
        assertThat(params.getValue(), arrayContaining((Object) "POL-00000042"));
    }

    @Test
    public void findByPolicyNumber_returnsNullWhenNoRows() throws Exception {
        stubQueryWithRows();

        assertThat(policyRepository.findByPolicyNumber("POL-00000001"), nullValue());
    }

    @Test
    public void findByPolicyNumber_returnsFirstRowWhenMultipleRowsMatch() throws Exception {
        ResultSet first = paddedPolicyRow();
        ResultSet second = paddedPolicyRow();
        when(second.getString("POLICY_TYPE")).thenReturn("AUT");
        stubQueryWithRows(first, second);

        Policy policy = policyRepository.findByPolicyNumber("POL-00000001");

        assertThat(policy.getPolicyType(), is("HOM"));
    }

    @Test
    public void findCoveragesByPolicyNumber_trimsAndMapsEveryColumn() throws Exception {
        ResultSet rs = paddedCoverageRow();
        stubQueryWithRows(rs);

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000001");

        assertThat(coverages, hasSize(1));
        Coverage coverage = coverages.get(0);
        assertThat(coverage.getPolicyNumber(), is("POL-00000001"));
        assertThat(coverage.getSequenceNum(), is(1));
        assertThat(coverage.getCoverageType(), is("DWEL"));
        assertThat(coverage.getDescription(), is("Dwelling Coverage"));
        assertThat(coverage.getCoverageLimit(), is(new BigDecimal("400000.00")));
        assertThat(coverage.getDeductible(), is(new BigDecimal("1000.00")));
        assertThat(coverage.getPremium(), is(new BigDecimal("850.00")));
        assertThat(coverage.getEffectiveDate(), is((java.util.Date) Date.valueOf("2025-01-01")));
        assertThat(coverage.getExpiryDate(), is((java.util.Date) Date.valueOf("2026-01-01")));
        assertThat(coverage.getStatus(), is("AC"));
        assertThat(coverage.getCoinsurancePct(), is(80));
        assertThat(coverage.getRatingTerritory(), is("IL0627"));
        assertThat(coverage.getClassCode(), is("R1001"));
    }

    @Test
    public void findCoveragesByPolicyNumber_leavesNullOptionalColumnsNull() throws Exception {
        ResultSet rs = paddedCoverageRow();
        when(rs.getString("DESCRIPTION")).thenReturn(null);
        when(rs.getString("RATING_TERRITORY")).thenReturn(null);
        when(rs.getString("CLASS_CODE")).thenReturn(null);
        stubQueryWithRows(rs);

        Coverage coverage = policyRepository.findCoveragesByPolicyNumber("POL-00000001").get(0);

        assertThat(coverage.getDescription(), nullValue());
        assertThat(coverage.getRatingTerritory(), nullValue());
        assertThat(coverage.getClassCode(), nullValue());
        assertThat(coverage.getCoverageType(), is("DWEL"));
    }

    @Test
    public void findCoveragesByPolicyNumber_passesPolicyNumberAndOrdersBySequence() throws Exception {
        stubQueryWithRows();

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000077");

        assertThat(coverages, empty());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue(), containsString("FROM ACMEINS.COVERAGES"));
        assertThat(sql.getValue(), containsString("ORDER BY SEQUENCE_NUM"));
        assertThat(params.getValue(), arrayContaining((Object) "POL-00000077"));
    }

    @Test
    public void findCoveragesByPolicyNumber_preservesRowOrderFromDatabase() throws Exception {
        ResultSet first = paddedCoverageRow();
        ResultSet second = paddedCoverageRow();
        when(second.getInt("SEQUENCE_NUM")).thenReturn(2);
        when(second.getString("COVERAGE_TYPE")).thenReturn("PERS");
        stubQueryWithRows(first, second);

        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000001");

        assertThat(coverages, hasSize(2));
        assertThat(coverages.get(0).getSequenceNum(), is(1));
        assertThat(coverages.get(1).getSequenceNum(), is(2));
        assertThat(coverages.get(1).getCoverageType(), is("PERS"));
    }

    @SuppressWarnings("unchecked")
    private void stubQueryWithRows(final ResultSet... rows) {
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenAnswer(new Answer<List<Object>>() {
                    @Override
                    public List<Object> answer(InvocationOnMock invocation) throws Throwable {
                        RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArguments()[2];
                        List<Object> mapped = new ArrayList<Object>();
                        for (int i = 0; i < rows.length; i++) {
                            mapped.add(mapper.mapRow(rows[i], i));
                        }
                        return mapped;
                    }
                });
    }

    private static ResultSet paddedPolicyRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("POLICY_NUMBER")).thenReturn("POL-00000001");
        when(rs.getString("POLICY_TYPE")).thenReturn("HOM");
        when(rs.getString("POLICY_STATUS")).thenReturn("AC");
        when(rs.getDate("EFFECTIVE_DATE")).thenReturn(Date.valueOf("2025-01-01"));
        when(rs.getDate("EXPIRY_DATE")).thenReturn(Date.valueOf("2026-01-01"));
        when(rs.getString("POLICYHOLDER_ID")).thenReturn("C000000001");
        when(rs.getString("AGENT_CODE")).thenReturn("AG1001");
        when(rs.getString("BRANCH_CODE")).thenReturn("CHI1  ");
        when(rs.getBigDecimal("TOTAL_PREMIUM")).thenReturn(new BigDecimal("1250.00"));
        when(rs.getBigDecimal("DEDUCTIBLE")).thenReturn(new BigDecimal("1000.00"));
        when(rs.getBigDecimal("COVERAGE_LIMIT")).thenReturn(new BigDecimal("500000.00"));
        when(rs.getDate("INCEPTION_DATE")).thenReturn(Date.valueOf("2020-01-01"));
        when(rs.getInt("RENEWAL_COUNT")).thenReturn(5);
        when(rs.getString("UW_STATUS")).thenReturn("AP");
        when(rs.getInt("RISK_SCORE")).thenReturn(35);
        when(rs.getString("WEB_INDICATOR")).thenReturn("Y");
        when(rs.getString("API_FLAG")).thenReturn("N");
        when(rs.getTimestamp("LAST_UPDATED")).thenReturn(Timestamp.valueOf("2025-01-02 03:04:05"));
        when(rs.getString("UPDATED_BY")).thenReturn("TNGUYEN ");
        return padStrings(rs, "POLICY_NUMBER", "POLICY_TYPE", "POLICY_STATUS",
                "POLICYHOLDER_ID", "AGENT_CODE", "UW_STATUS", "WEB_INDICATOR", "API_FLAG");
    }

    private static ResultSet paddedCoverageRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("POLICY_NUMBER")).thenReturn("POL-00000001");
        when(rs.getInt("SEQUENCE_NUM")).thenReturn(1);
        when(rs.getString("COVERAGE_TYPE")).thenReturn("DWEL");
        when(rs.getString("DESCRIPTION")).thenReturn("Dwelling Coverage");
        when(rs.getBigDecimal("COVERAGE_LIMIT")).thenReturn(new BigDecimal("400000.00"));
        when(rs.getBigDecimal("DEDUCTIBLE")).thenReturn(new BigDecimal("1000.00"));
        when(rs.getBigDecimal("PREMIUM")).thenReturn(new BigDecimal("850.00"));
        when(rs.getDate("EFFECTIVE_DATE")).thenReturn(Date.valueOf("2025-01-01"));
        when(rs.getDate("EXPIRY_DATE")).thenReturn(Date.valueOf("2026-01-01"));
        when(rs.getString("STATUS")).thenReturn("AC");
        when(rs.getInt("COINSURANCE_PCT")).thenReturn(80);
        when(rs.getString("RATING_TERRITORY")).thenReturn("IL0627");
        when(rs.getString("CLASS_CODE")).thenReturn("R1001");
        return padStrings(rs, "POLICY_NUMBER", "COVERAGE_TYPE", "DESCRIPTION", "STATUS",
                "RATING_TERRITORY", "CLASS_CODE");
    }

    /** Re-stubs the given string columns with DB2-style trailing blank padding. */
    private static ResultSet padStrings(ResultSet rs, String... columns) throws Exception {
        for (String column : Arrays.asList(columns)) {
            String value = rs.getString(column);
            when(rs.getString(eq(column))).thenReturn(value + "    ");
        }
        return rs;
    }
}
