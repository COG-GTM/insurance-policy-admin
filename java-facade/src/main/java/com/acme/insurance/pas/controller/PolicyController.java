package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.dto.PolicyResponse;
import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
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
 *
 * NOTE: /api/** requires `Authorization: Bearer <token>`; valid
 * tokens come from the `pas.security.api-tokens` property (env
 * `PAS_API_TOKENS`, comma-separated for rotation). Internal
 * underwriting/audit fields are intentionally not exposed on the
 * policy response; policy numbers are not authorization.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    @Autowired
    private PolicyRepository policyRepository;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<PolicyResponse> getPolicy(
            @PathVariable String policyNumber) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null) {
            return new ResponseEntity<PolicyResponse>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<PolicyResponse>(
                PolicyResponse.from(policy), HttpStatus.OK);
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
}
