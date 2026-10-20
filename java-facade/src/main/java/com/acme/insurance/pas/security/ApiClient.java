package com.acme.insurance.pas.security;

import com.acme.insurance.pas.model.Policy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ApiClient {

    public static final String REQUEST_ATTRIBUTE =
            "com.acme.insurance.pas.security.ApiClient";

    private final String id;
    private final Set<String> branchCodes;

    public ApiClient(String id, Set<String> branchCodes) {
        this.id = id;
        this.branchCodes = Collections.unmodifiableSet(
                new HashSet<String>(branchCodes));
    }

    public String getId() {
        return id;
    }

    public Set<String> getBranchCodes() {
        return branchCodes;
    }

    public boolean canRead(Policy policy) {
        return "Y".equals(policy.getApiFlag())
                && (branchCodes.contains("*")
                || (policy.getBranchCode() != null
                && branchCodes.contains(policy.getBranchCode())));
    }
}
