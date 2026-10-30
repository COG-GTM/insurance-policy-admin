package com.acme.insurance.pas.security;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/** An authenticated policy API consumer and the branches it may read. */
public class ApiClient {

    public static final String ALL_BRANCHES = "*";

    private final String id;
    private final Set<String> branches;

    public ApiClient(String id, Set<String> branches) {
        this.id = id;
        this.branches = Collections.unmodifiableSet(branches);
    }

    public String getId() {
        return id;
    }

    public Set<String> getBranches() {
        return branches;
    }

    public boolean canReadBranch(String branchCode) {
        if (branches.contains(ALL_BRANCHES)) {
            return true;
        }
        if (branchCode == null || branchCode.trim().isEmpty()) {
            return false;
        }
        return branches.contains(branchCode.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return id;
    }
}
