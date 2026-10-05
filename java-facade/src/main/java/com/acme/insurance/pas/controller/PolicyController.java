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

    private static final String ACTIVE_STATUS = "AC";

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
     * Fresh PREMBAT premium calculation (paragraph 3200), computed on demand in
     * Java from the policy type. This is not a breakdown of the stored
     * POLICIES.TOTAL_PREMIUM, which may include coverage-level and manual
     * adjustments. Read-only: nothing is written to PREMIUMS.
     *
     * Like PREMBAT's POL_CURSOR, only active ('AC') policies are rated; other
     * statuses return 422 Unprocessable Entity.
     */
    @GetMapping("/{policyNumber}/premium-calculation")
    public ResponseEntity<PremiumBreakdown> getPremiumCalculation(
            @PathVariable String policyNumber) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null) {
            return new ResponseEntity<PremiumBreakdown>(HttpStatus.NOT_FOUND);
        }
        if (!ACTIVE_STATUS.equals(trim(policy.getPolicyStatus()))) {
            return new ResponseEntity<PremiumBreakdown>(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return new ResponseEntity<PremiumBreakdown>(
                premiumCalculator.calculate(policy.getPolicyType()), HttpStatus.OK);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
