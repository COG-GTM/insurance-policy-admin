package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link CoverageEntity}. */
public interface CoverageRepository extends JpaRepository<CoverageEntity, CoverageId> {}
