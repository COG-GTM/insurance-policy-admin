package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link EndorsementEntity}. */
public interface EndorsementRepository extends JpaRepository<EndorsementEntity, EndorsementId> {}
