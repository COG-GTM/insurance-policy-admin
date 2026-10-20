package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Policy Controller - Read-only REST endpoints for policy data.
 *
 * This controller exposes policy data from the DB2 mainframe database
 * via REST/JSON. It is strictly read-only; all policy mutations
 * go through CICS transactions on the mainframe.
 *
 * Endpoints:
 *   GET /api/v1/policies/{policyNumber}           - Policy details
 *   GET /api/v1/policies/{policyNumber}/coverages  - Coverage details
 *
 * NOTE: Requests require HTTP Basic client credentials and are scoped to
 * the client's configured branches.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    @Autowired
    private PolicyRepository policyRepository;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<Policy> getPolicy(
            @PathVariable String policyNumber, Authentication authentication) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (!isEntitled(policy, authentication)) {
            return new ResponseEntity<Policy>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<Policy>(policy, HttpStatus.OK);
    }

    @GetMapping("/{policyNumber}/coverages")
    public ResponseEntity<List<Coverage>> getCoverages(
            @PathVariable String policyNumber, Authentication authentication) {
        // First verify the policy exists
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (!isEntitled(policy, authentication)) {
            return new ResponseEntity<List<Coverage>>(HttpStatus.NOT_FOUND);
        }
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber(
                policyNumber);
        return new ResponseEntity<List<Coverage>>(coverages, HttpStatus.OK);
    }

    private boolean isEntitled(Policy policy, Authentication authentication) {
        if (policy == null || authentication == null
                || !StringUtils.hasText(policy.getBranchCode())) {
            return false;
        }
        String branchAuthority = "BRANCH_"
                + policy.getBranchCode().trim().toUpperCase(Locale.ROOT);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("BRANCH_*".equals(authority.getAuthority())
                    || branchAuthority.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
