package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link ClassFactorEntity}. */
public interface ClassFactorRepository extends JpaRepository<ClassFactorEntity, ClassFactorId> {}
