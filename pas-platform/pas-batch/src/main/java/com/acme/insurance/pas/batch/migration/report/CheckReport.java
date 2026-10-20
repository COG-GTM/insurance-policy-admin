package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.triage.Classification;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one reconciliation or parallel-run check: the machine-readable report body.
 *
 * @param status {@code PASS} (zero unexplained), {@code FAIL}, or {@code INCOMPLETE} (an input was
 *     not available, e.g. AB-523 shadow premiums)
 * @param provenance where the compared data came from; makes stand-in runs explicit
 * @param sections check-specific detail (row counts, totals, hash summaries, outputs)
 */
public record CheckReport(
    String runId,
    CheckType checkType,
    LocalDate businessDate,
    Instant startedAt,
    Instant finishedAt,
    String status,
    List<String> provenance,
    Map<String, Object> sections,
    Map<Classification, Integer> classificationCounts,
    int unexplained,
    List<Difference> differences) {

  public CheckReport {
    provenance = List.copyOf(provenance);
    sections = Map.copyOf(sections);
    classificationCounts = Map.copyOf(classificationCounts);
    differences = List.copyOf(differences);
  }

  /** Builds a report, deriving classification counts, unexplained count and status. */
  public static CheckReport of(
      String runId,
      CheckType type,
      LocalDate businessDate,
      Instant startedAt,
      Instant finishedAt,
      boolean complete,
      List<String> provenance,
      Map<String, Object> sections,
      List<Difference> differences) {
    Map<Classification, Integer> counts = new EnumMap<>(Classification.class);
    for (Classification c : Classification.values()) {
      counts.put(c, 0);
    }
    differences.forEach(d -> counts.merge(d.classification(), 1, Integer::sum));
    int unexplained = counts.get(Classification.BUG) + counts.get(Classification.UNCLASSIFIED);
    String status = unexplained > 0 ? "FAIL" : complete ? "PASS" : "INCOMPLETE";
    return new CheckReport(
        runId,
        type,
        businessDate,
        startedAt,
        finishedAt,
        status,
        provenance,
        sections,
        counts,
        unexplained,
        differences);
  }

  public int count(Classification c) {
    return classificationCounts.getOrDefault(c, 0);
  }

  public boolean passed() {
    return "PASS".equals(status);
  }
}
