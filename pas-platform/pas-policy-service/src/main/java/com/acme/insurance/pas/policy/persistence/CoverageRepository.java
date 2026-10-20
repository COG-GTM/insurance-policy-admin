package com.acme.insurance.pas.policy.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link CoverageEntity}. */
public interface CoverageRepository extends JpaRepository<CoverageEntity, CoverageId> {

  /** A policy's coverages in {@code SEQUENCE_NUM} order (POLQRY / facade order). */
  List<CoverageEntity> findByIdPolicyNumberOrderByIdSequenceNumAsc(String policyNumber);
}
