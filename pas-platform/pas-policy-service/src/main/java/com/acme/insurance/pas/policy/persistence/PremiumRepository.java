package com.acme.insurance.pas.policy.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link PremiumEntity}. */
public interface PremiumRepository extends JpaRepository<PremiumEntity, PremiumId> {

  /** Premium history of a policy, newest term first (Broker Portal, AB-528). */
  List<PremiumEntity> findByIdPolicyNumberOrderByIdTermEffectiveDateDescIdCoverageSeqAsc(
      String policyNumber);
}
