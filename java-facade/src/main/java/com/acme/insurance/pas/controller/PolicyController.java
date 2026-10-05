package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.rating.PremiumBreakdown;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.repository.PolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 *   GET /api/v1/policies/{policyNumber}/premium-calculation
 *                                                  - PREMBAT premium breakdown
 *
 * NOTE: No authentication on these endpoints - relies on network
 * segmentation (internal VPN only). TODO: Add OAuth2 in Phase 2.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PremiumCalculator premiumCalculator;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<Policy> getPolicy(@PathVariable String policyNumber) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null) {
            return new ResponseEntity<Policy>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<Policy>(policy, HttpStatus.OK);
    }

    @GetMapping("/{policyNumber}/coverages")
    public ResponseEntity<List<Coverage>> getCoverages(
            @PathVariable String policyNumber) {
        // First verify the policy exists
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null) {
            return new ResponseEntity<List<Coverage>>(HttpStatus.NOT_FOUND);
        }
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber(
                policyNumber);
        return new ResponseEntity<List<Coverage>>(coverages, HttpStatus.OK);
    }

    /**
     * Premium breakdown as calculated by the PREMBAT batch program, computed
     * on demand in Java. Read-only: nothing is written to PREMIUMS.
     */
    @GetMapping("/{policyNumber}/premium-calculation")
    public ResponseEntity<PremiumBreakdown> getPremiumCalculation(
            @PathVariable String policyNumber) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null) {
            return new ResponseEntity<PremiumBreakdown>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<PremiumBreakdown>(
                premiumCalculator.calculate(policy.getPolicyType()), HttpStatus.OK);
    }
}
