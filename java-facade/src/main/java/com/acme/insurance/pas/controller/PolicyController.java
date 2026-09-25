package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import com.acme.insurance.pas.security.PolicyAuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
 * Callers must authenticate (see SecurityConfig) and are only served
 * policies covered by their entitlements; policies outside a caller's
 * scope are reported as 404 so the endpoints cannot be used to probe
 * which policy numbers exist.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PolicyAuthorizationService policyAuthorizationService;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<Policy> getPolicy(@PathVariable String policyNumber,
                                            Authentication authentication) {
        Policy policy = findAuthorizedPolicy(policyNumber, authentication);
        if (policy == null) {
            return new ResponseEntity<Policy>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<Policy>(policy, HttpStatus.OK);
    }

    @GetMapping("/{policyNumber}/coverages")
    public ResponseEntity<List<Coverage>> getCoverages(
            @PathVariable String policyNumber,
            Authentication authentication) {
        Policy policy = findAuthorizedPolicy(policyNumber, authentication);
        if (policy == null) {
            return new ResponseEntity<List<Coverage>>(HttpStatus.NOT_FOUND);
        }
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber(
                policyNumber);
        return new ResponseEntity<List<Coverage>>(coverages, HttpStatus.OK);
    }

    private Policy findAuthorizedPolicy(String policyNumber,
                                        Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (!policyAuthorizationService.isAuthorized(
                authentication.getName(), policy)) {
            return null;
        }
        return policy;
    }
}
