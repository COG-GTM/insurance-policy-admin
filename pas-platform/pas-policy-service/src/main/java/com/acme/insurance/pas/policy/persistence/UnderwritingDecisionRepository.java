package com.acme.insurance.pas.policy.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link UnderwritingDecisionEntity}. */
public interface UnderwritingDecisionRepository
    extends JpaRepository<UnderwritingDecisionEntity, UnderwritingDecisionId> {}
