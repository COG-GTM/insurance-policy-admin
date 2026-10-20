package com.acme.insurance.pas.policy.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link UnderwritingDecisionEntity}. */
public interface UnderwritingDecisionRepository
    extends JpaRepository<UnderwritingDecisionEntity, Long> {

  List<UnderwritingDecisionEntity> findByPolicyNumberAndDecisionDateOrderByDecisionIdAsc(
      String policyNumber, LocalDate decisionDate);

  List<UnderwritingDecisionEntity> findByPolicyNumberOrderByDecisionDateAscDecisionIdAsc(
      String policyNumber);
}
