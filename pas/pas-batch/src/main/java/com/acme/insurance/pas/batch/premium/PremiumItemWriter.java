package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.PremiumRunJdbcRepository;
import java.time.LocalDate;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * Writes one chunk in the chunk's transaction: the PREMIUMS rows of the rated policies (replacing
 * an existing row of the same key, Q-26) and the outcome of every policy in the chunk.
 */
public class PremiumItemWriter implements ItemWriter<PremiumOutcome> {

  private final PremiumRunJdbcRepository repository;
  private final long runId;
  private final LocalDate businessDate;

  public PremiumItemWriter(
      PremiumRunJdbcRepository repository, long runId, LocalDate businessDate) {
    this.repository = repository;
    this.runId = runId;
    this.businessDate = businessDate;
  }

  @Override
  public void write(Chunk<? extends PremiumOutcome> chunk) {
    repository.upsertPremiums(
        chunk.getItems().stream()
            .filter(PremiumOutcome::rated)
            .map(PremiumOutcome::premium)
            .toList());
    repository.recordOutcomes(
        runId, businessDate, chunk.getItems().stream().map(PremiumOutcome::runItem).toList());
  }
}
