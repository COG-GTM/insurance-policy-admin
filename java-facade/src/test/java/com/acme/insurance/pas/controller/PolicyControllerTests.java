package com.acme.insurance.pas.controller;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    public void getPolicy_commercialPolicy_returnsAllMappedFields() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-00000003")))
                .andExpect(jsonPath("$.policyType", is("CGL")))
                .andExpect(jsonPath("$.policyholderId", is("C000000003")))
                .andExpect(jsonPath("$.agentCode", is("AG3010")))
                .andExpect(jsonPath("$.branchCode", is("DET1")))
                .andExpect(jsonPath("$.totalPremium", is(4500.0)))
                .andExpect(jsonPath("$.deductible", is(2500.0)))
                .andExpect(jsonPath("$.coverageLimit", is(2000000.0)))
                .andExpect(jsonPath("$.renewalCount", is(3)))
                .andExpect(jsonPath("$.uwStatus", is("AP")))
                .andExpect(jsonPath("$.riskScore", is(55)))
                .andExpect(jsonPath("$.webIndicator", is("Y")))
                .andExpect(jsonPath("$.apiFlag", is("Y")))
                .andExpect(jsonPath("$.updatedBy", is("TNGUYEN")));
    }

    @Test
    public void getCoverages_autoPolicy_returnsCoverageDetails() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000002/coverages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].policyNumber", is("POL-00000002")))
                .andExpect(jsonPath("$[0].sequenceNum", is(1)))
                .andExpect(jsonPath("$[0].coverageType", is("LIAB")))
                .andExpect(jsonPath("$[0].description", is("Liability")))
                .andExpect(jsonPath("$[0].premium", is(450.5)))
                .andExpect(jsonPath("$[0].deductible", is(0.0)))
                .andExpect(jsonPath("$[0].coinsurancePct", is(100)))
                .andExpect(jsonPath("$[0].ratingTerritory", is("IL0601")))
                .andExpect(jsonPath("$[0].classCode", is("A2010")))
                .andExpect(jsonPath("$[1].sequenceNum", is(2)))
                .andExpect(jsonPath("$[1].coverageType", is("COLL")));
    }
}
