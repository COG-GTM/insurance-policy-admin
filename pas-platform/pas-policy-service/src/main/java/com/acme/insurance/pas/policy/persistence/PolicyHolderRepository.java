package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.policy.pii.BlindIndexPurpose;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** POLICY_HOLDERS access. Look up regulated fields only through their blind index. */
public interface PolicyHolderRepository extends JpaRepository<PolicyHolderEntity, String> {

  List<PolicyHolderEntity> findByTaxIdBlindIndex(String taxIdBlindIndex);

  List<PolicyHolderEntity> findBySsnLast4BlindIndex(String ssnLast4BlindIndex);

  default List<PolicyHolderEntity> findByTaxId(BlindIndexer indexer, String taxId) {
    return findByTaxIdBlindIndex(indexer.index(BlindIndexPurpose.TAX_ID, taxId));
  }
}
