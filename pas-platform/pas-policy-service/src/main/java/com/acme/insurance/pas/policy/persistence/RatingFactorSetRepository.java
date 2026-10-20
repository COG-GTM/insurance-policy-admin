package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RatingFactorSetEntity}. */
public interface RatingFactorSetRepository extends JpaRepository<RatingFactorSetEntity, String> {}
