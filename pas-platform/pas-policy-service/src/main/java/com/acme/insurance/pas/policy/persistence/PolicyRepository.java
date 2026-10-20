package com.acme.insurance.pas.policy.persistence;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link PolicyEntity}. */
public interface PolicyRepository extends JpaRepository<PolicyEntity, String> {

  /**
   * UNDWRT {@code 4000-CHECK-ACCUMULATION}: total coverage limit of active ({@code AC}) policies in
   * a branch. The {@code bpchar} cast keeps CHAR blank-padding semantics for the comparison.
   */
  @Query(
      value =
          "SELECT COALESCE(SUM(coverage_limit), 0) FROM pas.policies"
              + " WHERE branch_code = CAST(:branch AS bpchar) AND policy_status = 'AC'",
      nativeQuery = true)
  BigDecimal sumActiveCoverageLimitByBranch(@Param("branch") String branchCode);
}
