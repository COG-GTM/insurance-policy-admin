package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /**
   * Broker Portal book listing (AB-528): policies of an agent and/or branch, optionally by status,
   * in policy-number order. Null filters are ignored. Codes are compared trimmed and upper-case,
   * like {@code CallerEntitlements}. The caller's entitlements are applied before {@code LIMIT}/
   * {@code OFFSET} so pages index only visible policies: {@code wholeBook}, or the agent code is in
   * {@code entitledAgents}, or the branch code is in {@code entitledBranches} (neither list may be
   * empty; pad with a value that matches no code).
   */
  @Query(
      value =
          "SELECT * FROM pas.policies"
              + " WHERE (CAST(:agent AS varchar) IS NULL"
              + " OR UPPER(TRIM(agent_code)) = CAST(:agent AS varchar))"
              + " AND (CAST(:branch AS varchar) IS NULL"
              + " OR UPPER(TRIM(branch_code)) = CAST(:branch AS varchar))"
              + " AND (CAST(:status AS varchar) IS NULL OR policy_status = CAST(:status AS bpchar))"
              + " AND (:wholeBook"
              + " OR UPPER(TRIM(agent_code)) IN (:entitledAgents)"
              + " OR UPPER(TRIM(branch_code)) IN (:entitledBranches))"
              + " ORDER BY policy_number LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<PolicyEntity> findBook(
      @Param("agent") String agentCode,
      @Param("branch") String branchCode,
      @Param("status") String status,
      @Param("wholeBook") boolean wholeBook,
      @Param("entitledAgents") Collection<String> entitledAgents,
      @Param("entitledBranches") Collection<String> entitledBranches,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /**
   * Reads the policy with a row lock ({@code SELECT ... FOR UPDATE}), the equivalent of POLEND's
   * {@code WITH RS USE AND KEEP UPDATE LOCKS}: concurrent renewals/endorsements of one policy are
   * serialised and each sees the previous one's committed premium and version.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM PolicyEntity p WHERE p.policyNumber = :policyNumber")
  Optional<PolicyEntity> findForUpdate(@Param("policyNumber") String policyNumber);
}
