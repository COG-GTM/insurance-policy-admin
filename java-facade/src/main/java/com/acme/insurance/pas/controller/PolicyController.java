package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import com.acme.insurance.pas.security.ApiClient;
import com.acme.insurance.pas.security.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
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
 * Callers must present a configured API key (X-API-Key, see SecurityConfig)
 * and can only read policies in the branches assigned to their client.
 * Policies outside the caller's branches are reported as 404 so the API
 * does not reveal which policy numbers exist. Every request is audited
 * with the caller's client id.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    @Autowired
    private PolicyRepository policyRepository;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<Policy> getPolicy(@PathVariable String policyNumber,
                                            Authentication authentication,
                                            HttpServletRequest request) {
        ApiClient client = clientOf(authentication);
        Policy policy = findReadablePolicy(policyNumber, client);
        HttpStatus status = policy == null ? HttpStatus.NOT_FOUND : HttpStatus.OK;
        audit(client, request, status);
        if (policy == null) {
            return new ResponseEntity<Policy>(status);
        }
        return new ResponseEntity<Policy>(policy, status);
    }

    @GetMapping("/{policyNumber}/coverages")
    public ResponseEntity<List<Coverage>> getCoverages(
            @PathVariable String policyNumber,
            Authentication authentication,
            HttpServletRequest request) {
        ApiClient client = clientOf(authentication);
        // First verify the policy exists and is in the caller's branches
        Policy policy = findReadablePolicy(policyNumber, client);
        if (policy == null) {
            audit(client, request, HttpStatus.NOT_FOUND);
            return new ResponseEntity<List<Coverage>>(HttpStatus.NOT_FOUND);
        }
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber(
                policyNumber);
        audit(client, request, HttpStatus.OK);
        return new ResponseEntity<List<Coverage>>(coverages, HttpStatus.OK);
    }

    private Policy findReadablePolicy(String policyNumber, ApiClient client) {
        if (client == null) {
            return null;
        }
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null || !client.canReadBranch(policy.getBranchCode())) {
            return null;
        }
        return policy;
    }

    private static ApiClient clientOf(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof ApiClient) {
            return (ApiClient) authentication.getPrincipal();
        }
        return null;
    }

    private static void audit(ApiClient client, HttpServletRequest request, HttpStatus status) {
        AuditLog.record(client == null ? null : client.getId(), request.getMethod(),
                request.getRequestURI(), status.value(), request.getRemoteAddr());
    }
}
