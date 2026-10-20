package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link PremiumEntity}. */
public interface PremiumRepository extends JpaRepository<PremiumEntity, PremiumId> {}
