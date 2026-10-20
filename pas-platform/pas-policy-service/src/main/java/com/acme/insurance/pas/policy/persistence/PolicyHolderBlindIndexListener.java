package com.acme.insurance.pas.policy.persistence;

import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.policy.pii.BlindIndexPurpose;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/** Keeps the tax-id and SSN-last-4 blind indexes in step with their encrypted values. */
public class PolicyHolderBlindIndexListener {

  private final BlindIndexer indexer;

  public PolicyHolderBlindIndexListener(BlindIndexer indexer) {
    this.indexer = indexer;
  }

  @PrePersist
  @PreUpdate
  void refresh(PolicyHolderEntity holder) {
    holder.setTaxIdBlindIndex(indexer.index(BlindIndexPurpose.TAX_ID, holder.getTaxId()));
    holder.setSsnLast4BlindIndex(indexer.index(BlindIndexPurpose.SSN_LAST4, holder.getSsnLast4()));
  }
}
