package com.acme.insurance.pas.controller;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class PolicyControllerEdgeCaseTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void getCoverages_policyWithoutCoveragesReturnsEmptyArray() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, EFFECTIVE_DATE, " +
                "EXPIRY_DATE, POLICYHOLDER_ID) VALUES ('POL-T0000010', 'HOM', '2025-01-01', " +
                "'2026-01-01', 'C000000001')");

        mockMvc.perform(get("/api/v1/policies/POL-T0000010/coverages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    public void getPolicy_nullableFieldsSerializeAsNull() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO ACMEINS.POLICIES (POLICY_NUMBER, POLICY_TYPE, EFFECTIVE_DATE, " +
                "EXPIRY_DATE, POLICYHOLDER_ID) VALUES ('POL-T0000011', 'AUT', '2025-01-01', " +
                "'2026-01-01', 'C000000001')");

        mockMvc.perform(get("/api/v1/policies/POL-T0000011"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-T0000011")))
                .andExpect(jsonPath("$.policyStatus", is("PN")))
                .andExpect(jsonPath("$.agentCode", nullValue()))
                .andExpect(jsonPath("$.inceptionDate", nullValue()))
                .andExpect(jsonPath("$.totalPremium", is(0.0)))
                .andExpect(jsonPath("$.updatedBy", is("SYSTEM")));
    }

    @Test
    public void getPolicy_returnsSeededCommercialPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyType", is("CGL")))
                .andExpect(jsonPath("$.branchCode", is("DET1")))
                .andExpect(jsonPath("$.coverageLimit", is(2000000.0)))
                .andExpect(jsonPath("$.effectiveDate", is("2025-06-01")));
    }

    @Test
    public void policiesApi_isReadOnly() throws Exception {
        mockMvc.perform(post("/api/v1/policies/POL-00000001"))
                .andExpect(status().isMethodNotAllowed());
    }
}
