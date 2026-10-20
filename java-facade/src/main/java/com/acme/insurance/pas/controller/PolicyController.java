package com.acme.insurance.pas.controller;

import com.acme.insurance.pas.model.Coverage;
import com.acme.insurance.pas.model.Policy;
import com.acme.insurance.pas.repository.PolicyRepository;
import com.acme.insurance.pas.security.ApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

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
 * API access requires a client key and is scoped by branch and API_FLAG.
 *
 * @author T. Nguyen (2022)
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private static final Pattern POLICY_NUMBER_FORMAT =
            Pattern.compile("^[A-Z0-9-]{1,12}$");

    @Autowired
    private PolicyRepository policyRepository;

    @GetMapping("/{policyNumber}")
    public ResponseEntity<Policy> getPolicy(
            @PathVariable String policyNumber,
            @RequestAttribute(ApiClient.REQUEST_ATTRIBUTE) ApiClient client) {
        if (!POLICY_NUMBER_FORMAT.matcher(policyNumber).matches()) {
            return new ResponseEntity<Policy>(HttpStatus.BAD_REQUEST);
        }
        Policy policy = findReadablePolicy(policyNumber, client);
        if (policy == null) {
            return new ResponseEntity<Policy>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<Policy>(policy, HttpStatus.OK);
    }

    @GetMapping("/{policyNumber}/coverages")
    public ResponseEntity<List<Coverage>> getCoverages(
            @PathVariable String policyNumber,
            @RequestAttribute(ApiClient.REQUEST_ATTRIBUTE) ApiClient client) {
        if (!POLICY_NUMBER_FORMAT.matcher(policyNumber).matches()) {
            return new ResponseEntity<List<Coverage>>(HttpStatus.BAD_REQUEST);
        }
        Policy policy = findReadablePolicy(policyNumber, client);
        if (policy == null) {
            return new ResponseEntity<List<Coverage>>(HttpStatus.NOT_FOUND);
        }
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber(
                policyNumber);
        return new ResponseEntity<List<Coverage>>(coverages, HttpStatus.OK);
    }

    private Policy findReadablePolicy(String policyNumber, ApiClient client) {
        Policy policy = policyRepository.findByPolicyNumber(policyNumber);
        if (policy == null || !client.canRead(policy)) {
            return null;
        }
        return policy;
    }
}
