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
import org.springframework.transaction.annotation.Transactional;

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
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$.policyNumber", is("POL-00000001")))
                .andExpect(jsonPath("$.policyType", is("HOM")))
                .andExpect(jsonPath("$.policyStatus", is("AC")))
                .andExpect(jsonPath("$.totalPremium", is(1250.0)));
    }

    @Test
    public void getPolicy_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_returnsCoverages() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].coverageType", is("DWEL")))
                .andExpect(jsonPath("$[1].coverageType", is("PERS")));
    }

    @Test
    public void getCoverages_policyNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT/coverages"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getPremiumCalculation_returnsPrembatBreakdown() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/premium-calculation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyType", is("HOM")))
                .andExpect(jsonPath("$.basePremium", is(1200.0)))
                .andExpect(jsonPath("$.taxAmount", is(42.0)))
                .andExpect(jsonPath("$.surcharge", is(25.0)))
                .andExpect(jsonPath("$.finalPremium", is(1267.0)));
    }

    @Test
    public void getPremiumCalculation_unknownTypeUsesDefaultRate() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000003/premium-calculation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyType", is("CGL")))
                .andExpect(jsonPath("$.finalPremium", is(1060.0)));
    }

    @Test
    @Transactional
    public void getPremiumCalculation_inactivePolicyIsNotRated() throws Exception {
        jdbcTemplate.update("INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, "
                + "POLICY_STATUS, EFFECTIVE_DATE, EXPIRY_DATE, POLICYHOLDER_ID) "
                + "VALUES ('POL-CN000001', 'AUT', 'CN', '2024-01-01', '2025-01-01', 'C000000001')");
        mockMvc.perform(get("/api/v1/policies/POL-CN000001/premium-calculation"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void getPremiumCalculation_policyNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT/premium-calculation"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
