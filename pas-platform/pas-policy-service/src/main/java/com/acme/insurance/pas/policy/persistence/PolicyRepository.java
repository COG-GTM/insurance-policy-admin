package com.acme.insurance.pas.policy.persistence;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
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
   * in policy-number order. Null filters are ignored; callers filter by entitlement.
   */
  @Query(
      value =
          "SELECT * FROM pas.policies"
              + " WHERE (CAST(:agent AS varchar) IS NULL OR agent_code = CAST(:agent AS bpchar))"
              + " AND (CAST(:branch AS varchar) IS NULL OR branch_code = CAST(:branch AS bpchar))"
              + " AND (CAST(:status AS varchar) IS NULL OR policy_status = CAST(:status AS bpchar))"
              + " ORDER BY policy_number LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<PolicyEntity> findBook(
      @Param("agent") String agentCode,
      @Param("branch") String branchCode,
      @Param("status") String status,
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
