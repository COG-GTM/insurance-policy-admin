package com.acme.insurance.pas.underwriting;

import java.math.BigDecimal;

/**
 * UNDWRT {@code 4000-CHECK-ACCUMULATION}: the sum of COVERAGE_LIMIT over the branch's active (AC)
 * policies, including the policy being underwritten when it is active.
 */
public interface BranchExposure {

  BigDecimal activeCoverageLimit(String branchCode);
}
