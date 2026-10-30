package com.acme.insurance.pas.controller;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class PolicyControllerTests {

    private static final String API_KEY = "X-API-Key";
    private static final String CHI_KEY = "chi-test-key-0001";
    private static final String DET_KEY = "det-test-key-0002";
    private static final String ALL_KEY = "all-test-key-0003";

    @Autowired
    private MockMvc mockMvc;

    private static MockHttpServletRequestBuilder authed(String url, String key) {
        return get(url).header(API_KEY, key);
    }

    @Test
    public void getPolicy_returnsPolicy() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000001", CHI_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$.policyNumber", is("POL-00000001")))
                .andExpect(jsonPath("$.policyType", is("HOM")))
                .andExpect(jsonPath("$.policyStatus", is("AC")))
                .andExpect(jsonPath("$.totalPremium", is(1250.0)));
    }

    @Test
    public void getPolicy_notFound() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/NONEXISTENT", CHI_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_returnsCoverages() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000001/coverages", CHI_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].coverageType", is("DWEL")))
                .andExpect(jsonPath("$[1].coverageType", is("PERS")));
    }

    @Test
    public void getCoverages_policyNotFound() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/NONEXISTENT/coverages", CHI_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    public void healthCheck_returnsUp() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    public void getPolicy_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("X-API-Key")))
                .andExpect(content().string(not(containsString("POL-00000001"))));
    }

    @Test
    public void getCoverages_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_withInvalidApiKey_returns401() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000001", "not-a-real-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authed("/api/v1/policies/POL-00000001", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_withBasicAuthInsteadOfApiKey_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header("Authorization", "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void getPolicy_otherBranch_returns404() throws Exception {
        // POL-00000003 is a DET1 policy; the CHI1 client must not learn it exists
        mockMvc.perform(authed("/api/v1/policies/POL-00000003", CHI_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().string(isEmptyString()));
        mockMvc.perform(authed("/api/v1/policies/POL-00000001", DET_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    public void getCoverages_otherBranch_returns404() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000003/coverages", CHI_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().string(isEmptyString()));
    }

    @Test
    public void getPolicy_ownBranch_returnsPolicy() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000003", DET_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-00000003")))
                .andExpect(jsonPath("$.branchCode", is("DET1")));
    }

    @Test
    public void getPolicy_allBranchesClient_readsEveryBranch() throws Exception {
        mockMvc.perform(authed("/api/v1/policies/POL-00000001", ALL_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(authed("/api/v1/policies/POL-00000003/coverages", ALL_KEY))
                .andExpect(status().isOk());
    }

    @Test
    public void healthCheck_doesNotRequireApiKey() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk());
    }
}
