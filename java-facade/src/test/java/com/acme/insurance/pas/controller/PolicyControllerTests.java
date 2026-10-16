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

    private static final String AUTH = "Authorization";
    private static final String TOKEN = "Bearer test-token-1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void getPolicy_returnsPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header(AUTH, TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$.policyNumber", is("POL-00000001")))
                .andExpect(jsonPath("$.policyType", is("HOM")))
                .andExpect(jsonPath("$.policyStatus", is("AC")))
                .andExpect(jsonPath("$.totalPremium", is(1250.0)))
                .andExpect(jsonPath("$.policyholderId").exists())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.uwStatus").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.agentCode").doesNotExist())
                .andExpect(jsonPath("$.webIndicator").doesNotExist())
                .andExpect(jsonPath("$.apiFlag").doesNotExist());
    }

    @Test
    public void getPolicy_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT")
                        .header(AUTH, TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_returnsCoverages() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages")
                        .header(AUTH, TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].coverageType", is("DWEL")))
                .andExpect(jsonPath("$[1].coverageType", is("PERS")));
    }

    @Test
    public void getCoverages_policyNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT/coverages")
                        .header(AUTH, TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getPolicy_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"pas-facade\""));
    }

    @Test
    public void getCoverages_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"pas-facade\""));
    }

    @Test
    public void getPolicy_wrongToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header(AUTH, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_basicScheme_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header(AUTH, "Basic dGVzdA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_emptyBearerToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header(AUTH, "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_secondConfiguredToken_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header(AUTH, "Bearer test-token-2"))
                .andExpect(status().isOk());
    }

    @Test
    public void getPolicy_noAuthOnNonexistent_returns401() throws Exception {
        // No existence oracle: unauthenticated requests get 401, not 404.
        mockMvc.perform(get("/api/v1/policies/NONEXISTENT"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
