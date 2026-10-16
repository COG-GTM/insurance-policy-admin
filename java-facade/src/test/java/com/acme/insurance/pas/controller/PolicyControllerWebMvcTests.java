package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.NestedServletException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebMvcTest(PolicyController.class)
public class PolicyControllerWebMvcTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicyRepository policyRepository;

    @Test
    public void getPolicy_found_returnsRepositoryPolicyAsJson() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000042"))
                .willReturn(policy("POL-00000042", "AUT"));

        mockMvc.perform(get("/api/v1/policies/POL-00000042"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.policyNumber", is("POL-00000042")))
                .andExpect(jsonPath("$.policyType", is("AUT")))
                .andExpect(jsonPath("$.policyStatus", is("AC")))
                .andExpect(jsonPath("$.agentCode", is("AG9999")))
                .andExpect(jsonPath("$.totalPremium", is(1234.56)))
                .andExpect(jsonPath("$.renewalCount", is(4)))
                .andExpect(jsonPath("$.riskScore", is(61)));

        verify(policyRepository).findByPolicyNumber("POL-00000042");
        verifyNoMoreInteractions(policyRepository);
    }

    @Test
    public void getPolicy_nullOptionalFields_serializedAsNull() throws Exception {
        Policy policy = new Policy();
        policy.setPolicyNumber("POL-00000043");
        given(policyRepository.findByPolicyNumber("POL-00000043")).willReturn(policy);

        mockMvc.perform(get("/api/v1/policies/POL-00000043"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-00000043")))
                .andExpect(jsonPath("$.agentCode").value(nullValue()))
                .andExpect(jsonPath("$.totalPremium").value(nullValue()))
                .andExpect(jsonPath("$.renewalCount", is(0)));
    }

    @Test
    public void getPolicy_notFound_returns404WithEmptyBody() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000404")).willReturn(null);

        mockMvc.perform(get("/api/v1/policies/POL-00000404"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(policyRepository).findByPolicyNumber("POL-00000404");
        verifyNoMoreInteractions(policyRepository);
    }

    @Test
    public void getPolicy_passesPathVariableUnmodified() throws Exception {
        mockMvc.perform(get("/api/v1/policies/pol-00000001"))
                .andExpect(status().isNotFound());

        verify(policyRepository).findByPolicyNumber("pol-00000001");
    }

    @Test
    public void getPolicy_repositoryFailure_propagates() throws Exception {
        given(policyRepository.findByPolicyNumber(anyString()))
                .willThrow(new DataAccessResourceFailureException("DB2 unavailable"));

        try {
            mockMvc.perform(get("/api/v1/policies/POL-00000001"));
            fail("Expected repository failure to propagate");
        } catch (NestedServletException e) {
            assertThat(e.getCause(), instanceOf(DataAccessResourceFailureException.class));
        }
    }

    @Test
    public void getCoverages_policyFound_returnsCoveragesInRepositoryOrder() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000042"))
                .willReturn(policy("POL-00000042", "AUT"));
        given(policyRepository.findCoveragesByPolicyNumber("POL-00000042"))
                .willReturn(Arrays.asList(
                        coverage("POL-00000042", 1, "LIAB", "250000.00"),
                        coverage("POL-00000042", 2, "COLL", "50000.00")));

        mockMvc.perform(get("/api/v1/policies/POL-00000042/coverages"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sequenceNum", is(1)))
                .andExpect(jsonPath("$[0].coverageType", is("LIAB")))
                .andExpect(jsonPath("$[0].coverageLimit", is(250000.0)))
                .andExpect(jsonPath("$[0].coinsurancePct", is(80)))
                .andExpect(jsonPath("$[1].sequenceNum", is(2)))
                .andExpect(jsonPath("$[1].coverageType", is("COLL")));

        verify(policyRepository).findByPolicyNumber("POL-00000042");
        verify(policyRepository).findCoveragesByPolicyNumber("POL-00000042");
        verifyNoMoreInteractions(policyRepository);
    }

    @Test
    public void getCoverages_policyWithoutCoverages_returnsEmptyArray() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000044"))
                .willReturn(policy("POL-00000044", "HOM"));
        given(policyRepository.findCoveragesByPolicyNumber("POL-00000044"))
                .willReturn(Collections.<Coverage>emptyList());

        mockMvc.perform(get("/api/v1/policies/POL-00000044/coverages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    public void getCoverages_policyNotFound_doesNotQueryCoverages() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000404")).willReturn(null);

        mockMvc.perform(get("/api/v1/policies/POL-00000404/coverages"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(policyRepository).findByPolicyNumber("POL-00000404");
        verify(policyRepository, never()).findCoveragesByPolicyNumber(anyString());
    }

    @Test
    public void writeMethods_areNotSupported() throws Exception {
        mockMvc.perform(post("/api/v1/policies/POL-00000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/v1/policies/POL-00000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/policies/POL-00000001"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/policies/POL-00000001/coverages")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isMethodNotAllowed());

        verifyZeroInteractions(policyRepository);
    }

    private static Policy policy(String policyNumber, String type) {
        Policy policy = new Policy();
        policy.setPolicyNumber(policyNumber);
        policy.setPolicyType(type);
        policy.setPolicyStatus("AC");
        policy.setAgentCode("AG9999");
        policy.setTotalPremium(new BigDecimal("1234.56"));
        policy.setRenewalCount(4);
        policy.setRiskScore(61);
        return policy;
    }

    private static Coverage coverage(String policyNumber, int seq, String type, String limit) {
        Coverage coverage = new Coverage();
        coverage.setPolicyNumber(policyNumber);
        coverage.setSequenceNum(seq);
        coverage.setCoverageType(type);
        coverage.setCoverageLimit(new BigDecimal(limit));
        coverage.setCoinsurancePct(80);
        return coverage;
    }
}
