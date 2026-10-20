package com.acme.insurance.pas.controller;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class PolicyControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void getPolicy_returnsPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header("Authorization", "Bearer test-full-key-0001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$.policyNumber", is("POL-00000001")))
                .andExpect(jsonPath("$.policyType", is("HOM")))
                .andExpect(jsonPath("$.policyStatus", is("AC")))
                .andExpect(jsonPath("$.totalPremium", is(1250.0)));
    }

    @Test
    public void getPolicy_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT")
                        .header("Authorization", "Bearer test-full-key-0001"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_returnsCoverages() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages")
                        .header("Authorization", "Bearer test-full-key-0001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].coverageType", is("DWEL")))
                .andExpect(jsonPath("$[1].coverageType", is("PERS")));
    }

    @Test
    public void getCoverages_policyNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT/coverages")
                        .header("Authorization", "Bearer test-full-key-0001"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getPolicy_noCredentials_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", notNullValue()));

        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", notNullValue()));
    }

    @Test
    public void getPolicy_invalidKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header("Authorization", "Bearer wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_outOfScopeBranch_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000003")
                        .header("Authorization", "Bearer test-chi1-key-0001"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/policies/POL-00000003/coverages")
                        .header("Authorization", "Bearer test-chi1-key-0001"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header("Authorization", "Bearer test-chi1-key-0001"))
                .andExpect(status().isOk());
    }

    @Test
    public void getPolicy_apiFlagN_returns404() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, POLICY_STATUS, " +
                        "EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID, AGENT_CODE, BRANCH_CODE, " +
                        "TOTAL_PREMIUM, DEDUCTIBLE, COVERAGE_LIMIT, INCEPTION_DATE, RENEWAL_COUNT, " +
                        "UW_STATUS, RISK_SCORE, WEB_INDICATOR, API_FLAG, UPDATED_BY) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "POL-00000099", "HOM", "AC", "2025-01-01", "2026-01-01", "C000000001",
                "AG1001", "CHI1", 1250.00, 1000.00, 500000.00, "2020-01-01", 5,
                "AP", 35, "Y", "N", "TNGUYEN");
        try {
            mockMvc.perform(get("/api/v1/policies/POL-00000099")
                            .header("Authorization", "Bearer test-full-key-0001"))
                    .andExpect(status().isNotFound());
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM ACMEINS.POLICIES WHERE POLICY_NUMBER = ?",
                    "POL-00000099");
        }
    }

    @Test
    public void getPolicy_invalidFormat_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-0000000001X")
                        .header("Authorization", "Bearer test-full-key-0001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
