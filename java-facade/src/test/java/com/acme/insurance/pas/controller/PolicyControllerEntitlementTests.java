package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class PolicyControllerEntitlementTests {

    private static final String POLICY_NUMBER = "X";

    private PolicyRepository policyRepository;
    private MockMvc mockMvc;

    @Before
    public void setUp() {
        policyRepository = mock(PolicyRepository.class);
        PolicyController controller = new PolicyController();
        ReflectionTestUtils.setField(controller, "policyRepository", policyRepository);
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    public void wildcardClientCanReadPolicyWithNullBranchCode() throws Exception {
        Policy policy = policyWithBranchCode(null);
        when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(policy);

        mockMvc.perform(get("/api/v1/policies/" + POLICY_NUMBER)
                        .principal(authentication("BRANCH_*")))
                .andExpect(status().isOk());
    }

    @Test
    public void wildcardClientCanReadPolicyWithBlankBranchCode() throws Exception {
        Policy policy = policyWithBranchCode("  ");
        when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(policy);

        mockMvc.perform(get("/api/v1/policies/" + POLICY_NUMBER)
                        .principal(authentication("BRANCH_*")))
                .andExpect(status().isOk());
    }

    @Test
    public void scopedClientCannotReadPolicyWithNullBranchCode() throws Exception {
        assertScopedClientCannotReadPolicyWithBranchCode(null);
    }

    @Test
    public void scopedClientCannotReadPolicyWithBlankBranchCode() throws Exception {
        assertScopedClientCannotReadPolicyWithBranchCode("  ");
    }

    private void assertScopedClientCannotReadPolicyWithBranchCode(String branchCode)
            throws Exception {
        Policy policy = policyWithBranchCode(branchCode);
        when(policyRepository.findByPolicyNumber(POLICY_NUMBER)).thenReturn(policy);
        UsernamePasswordAuthenticationToken authentication = authentication("BRANCH_CHI1");

        mockMvc.perform(get("/api/v1/policies/" + POLICY_NUMBER)
                        .principal(authentication))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/policies/" + POLICY_NUMBER + "/coverages")
                        .principal(authentication))
                .andExpect(status().isNotFound());

        verify(policyRepository, never()).findCoveragesByPolicyNumber(POLICY_NUMBER);
    }

    private Policy policyWithBranchCode(String branchCode) {
        Policy policy = new Policy();
        policy.setBranchCode(branchCode);
        return policy;
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return new UsernamePasswordAuthenticationToken("c", null,
                Collections.singletonList(new SimpleGrantedAuthority(authority)));
    }
}
