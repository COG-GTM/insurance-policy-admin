package com.acme.insurance.pas.batch.migration.parallelrun;

import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.report.CheckType;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Daily parallel-run diff: every {@link OutputAdapter} scheduled for the business date is compared
 * record by record and each difference is classified through the {@link TriageRegister}. An output
 * whose legacy capture or new output is missing makes the day {@code INCOMPLETE}, never PASS.
 */
public final class ParallelRunService {

  public static final String CHECK = CheckType.PARALLEL_RUN.name();

  /** Per-output result. */
  public record OutputSummary(
      String output,
      String status,
      String legacyLocation,
      String newLocation,
      int legacyRecords,
      int newRecords,
      int matched,
      int differences) {}

  private final List<OutputAdapter> adapters;
  private final TriageRegister triage;
  private final List<String> provenance;
  private final Clock clock;

  public ParallelRunService(
      List<OutputAdapter> adapters, TriageRegister triage, List<String> provenance, Clock clock) {
    this.adapters = List.copyOf(adapters);
    this.triage = triage;
    this.provenance = List.copyOf(provenance);
    this.clock = clock;
  }

  public CheckReport run(LocalDate businessDate, String runId) {
    Instant started = clock.instant();
    List<Difference> differences = new ArrayList<>();
    List<OutputSummary> outputs = new ArrayList<>();
    boolean complete = true;
    for (OutputAdapter adapter : adapters) {
      if (!adapter.runsOn(businessDate)) {
        continue;
      }
      try {
        Optional<OutputAdapter.Records> legacy = adapter.legacy(businessDate);
        Optional<OutputAdapter.Records> candidate = adapter.candidate(businessDate);
        if (legacy.isEmpty() || candidate.isEmpty()) {
          complete = false;
          outputs.add(
              new OutputSummary(
                  adapter.scope(),
                  legacy.isEmpty() ? "LEGACY_NOT_AVAILABLE" : "NEW_NOT_AVAILABLE",
                  legacy.map(OutputAdapter.Records::location).orElse(null),
                  candidate.map(OutputAdapter.Records::location).orElse(null),
                  legacy.map(r -> r.byKey().size()).orElse(0),
                  candidate.map(r -> r.byKey().size()).orElse(0),
                  0,
                  0));
          continue;
        }
        outputs.add(compare(adapter, legacy.get(), candidate.get(), differences));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return CheckReport.of(
        runId,
        CheckType.PARALLEL_RUN,
        businessDate,
        started,
        clock.instant(),
        complete,
        provenance,
        Map.of("outputs", outputs),
        triage.classifyAll(differences));
  }

  private static OutputSummary compare(
      OutputAdapter adapter,
      OutputAdapter.Records legacy,
      OutputAdapter.Records candidate,
      List<Difference> differences) {
    int before = differences.size();
    int matched = 0;
    for (Map.Entry<String, String> e : legacy.byKey().entrySet()) {
      String other = candidate.byKey().get(e.getKey());
      if (other == null) {
        differences.add(
            Difference.unclassified(
                CHECK,
                adapter.scope(),
                "MISSING_IN_NEW",
                e.getKey(),
                List.of(),
                e.getValue(),
                null));
      } else if (other.equals(e.getValue())) {
        matched++;
      } else {
        differences.add(
            Difference.unclassified(
                CHECK,
                adapter.scope(),
                "CHANGED",
                e.getKey(),
                adapter.changedFields(e.getValue(), other),
                e.getValue(),
                other));
      }
    }
    for (Map.Entry<String, String> e : candidate.byKey().entrySet()) {
      if (!legacy.byKey().containsKey(e.getKey())) {
        differences.add(
            Difference.unclassified(
                CHECK, adapter.scope(), "EXTRA_IN_NEW", e.getKey(), List.of(), null, e.getValue()));
      }
    }
    int found = differences.size() - before;
    return new OutputSummary(
        adapter.scope(),
        found == 0 ? "MATCH" : "DIFFERENT",
        legacy.location(),
        candidate.location(),
        legacy.byKey().size(),
        candidate.byKey().size(),
        matched,
        found);
  }
}
