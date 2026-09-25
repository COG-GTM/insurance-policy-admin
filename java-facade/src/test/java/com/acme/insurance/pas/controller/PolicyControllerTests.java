package com.acme.insurance.pas.controller;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class PolicyControllerTests {

    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void getPolicy_returnsPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .with(httpBasic("test-admin", PASSWORD)))
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
                        .with(httpBasic("test-admin", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getPolicy_anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_badCredentialsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .with(httpBasic("test-admin", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_outsideEntitlementIsNotFound() throws Exception {
        // test-agent is scoped to AG1001; POL-00000002 belongs to AG2005.
        mockMvc.perform(get("/api/v1/policies/POL-00000002")
                        .with(httpBasic("test-agent", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getPolicy_withinEntitlementIsReturned() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .with(httpBasic("test-agent", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentCode", is("AG1001")));
    }

    @Test
    public void getCoverages_returnsCoverages() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages")
                        .with(httpBasic("test-admin", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].coverageType", is("DWEL")))
                .andExpect(jsonPath("$[1].coverageType", is("PERS")));
    }

    @Test
    public void getCoverages_policyNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT/coverages")
                        .with(httpBasic("test-admin", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getCoverages_outsideEntitlementIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000002/coverages")
                        .with(httpBasic("test-agent", PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    public void otherActuatorEndpoints_requireAuthentication() throws Exception {
        mockMvc.perform(get("/manage/env"))
                .andExpect(status().isUnauthorized());
    }
}
