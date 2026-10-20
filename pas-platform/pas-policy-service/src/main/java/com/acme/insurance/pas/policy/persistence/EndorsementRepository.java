package com.acme.insurance.pas.policy.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link EndorsementEntity}. */
public interface EndorsementRepository extends JpaRepository<EndorsementEntity, EndorsementId> {

  /** POLEND {@code SELECT COALESCE(MAX(ENDORSEMENT_SEQ), 0)}. */
  @Query(
      "SELECT COALESCE(MAX(e.id.endorsementSeq), 0) FROM EndorsementEntity e"
          + " WHERE e.id.policyNumber = :policyNumber")
  int maxSequence(@Param("policyNumber") String policyNumber);

  /** A policy's endorsements in sequence order. */
  List<EndorsementEntity> findByIdPolicyNumberOrderByIdEndorsementSeqAsc(String policyNumber);
}
