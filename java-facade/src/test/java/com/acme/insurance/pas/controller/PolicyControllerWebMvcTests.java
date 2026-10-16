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
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebMvcTest(PolicyController.class)
public class PolicyControllerWebMvcTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PolicyRepository policyRepository;

    @Test
    public void getPolicy_looksUpThePathPolicyNumberAndReturnsRepositoryResult() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000042")).willReturn(policy("POL-00000042"));

        mockMvc.perform(get("/api/v1/policies/POL-00000042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-00000042")))
                .andExpect(jsonPath("$.policyType", is("CGL")))
                .andExpect(jsonPath("$.totalPremium", is(4500.0)))
                .andExpect(jsonPath("$.agentCode").value(nullValue()));

        verify(policyRepository).findByPolicyNumber("POL-00000042");
        verifyNoMoreInteractions(policyRepository);
    }

    @Test
    public void getPolicy_notFoundReturnsEmptyBody() throws Exception {
        given(policyRepository.findByPolicyNumber(anyString())).willReturn(null);

        mockMvc.perform(get("/api/v1/policies/POL-00000099"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(isEmptyString()));
    }

    @Test
    public void getCoverages_doesNotQueryCoveragesWhenPolicyIsMissing() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000099")).willReturn(null);

        mockMvc.perform(get("/api/v1/policies/POL-00000099/coverages"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(isEmptyString()));

        verify(policyRepository, never()).findCoveragesByPolicyNumber(anyString());
    }

    @Test
    public void getCoverages_returnsEmptyArrayWhenPolicyHasNoCoverages() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000042")).willReturn(policy("POL-00000042"));
        given(policyRepository.findCoveragesByPolicyNumber("POL-00000042"))
                .willReturn(Collections.<Coverage>emptyList());

        mockMvc.perform(get("/api/v1/policies/POL-00000042/coverages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    public void getCoverages_returnsCoveragesInRepositoryOrder() throws Exception {
        given(policyRepository.findByPolicyNumber("POL-00000042")).willReturn(policy("POL-00000042"));
        given(policyRepository.findCoveragesByPolicyNumber("POL-00000042"))
                .willReturn(Arrays.asList(coverage(1, "PREM"), coverage(2, "PROD")));

        mockMvc.perform(get("/api/v1/policies/POL-00000042/coverages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sequenceNum", is(1)))
                .andExpect(jsonPath("$[0].coverageType", is("PREM")))
                .andExpect(jsonPath("$[0].premium", is(1700.0)))
                .andExpect(jsonPath("$[1].sequenceNum", is(2)))
                .andExpect(jsonPath("$[1].coverageType", is("PROD")));

        verify(policyRepository).findByPolicyNumber("POL-00000042");
        verify(policyRepository).findCoveragesByPolicyNumber("POL-00000042");
    }

    @Test
    public void getPolicy_propagatesDatabaseFailure() throws Exception {
        given(policyRepository.findByPolicyNumber(anyString()))
                .willThrow(new DataAccessResourceFailureException("DB2 unavailable"));

        try {
            mockMvc.perform(get("/api/v1/policies/POL-00000042"));
            org.junit.Assert.fail("Expected DataAccessResourceFailureException");
        } catch (Exception e) {
            org.junit.Assert.assertThat(e.getCause(),
                    instanceOf(DataAccessResourceFailureException.class));
        }
    }

    @Test
    public void policiesEndpoint_isReadOnly() throws Exception {
        mockMvc.perform(post("/api/v1/policies/POL-00000042"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/v1/policies/POL-00000042/coverages"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoMoreInteractions(policyRepository);
    }

    private static Policy policy(String policyNumber) {
        Policy policy = new Policy();
        policy.setPolicyNumber(policyNumber);
        policy.setPolicyType("CGL");
        policy.setPolicyStatus("AC");
        policy.setTotalPremium(new BigDecimal("4500.00"));
        return policy;
    }

    private static Coverage coverage(int sequenceNum, String coverageType) {
        Coverage coverage = new Coverage();
        coverage.setPolicyNumber("POL-00000042");
        coverage.setSequenceNum(sequenceNum);
        coverage.setCoverageType(coverageType);
        coverage.setPremium(new BigDecimal("1700.00"));
        return coverage;
    }
}
