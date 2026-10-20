package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link TerritoryFactorEntity}. */
public interface TerritoryFactorRepository
    extends JpaRepository<TerritoryFactorEntity, TerritoryFactorId> {}
