package com.acme.insurance.pas.batch.migration.parallel;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this service.")
public class TriageService {
  private final JdbcTemplate jdbc;
  private final QuirkRegister quirks;
  private final AtomicLong untriagedGauge;
  private final AtomicLong bugGauge;

  public TriageService(JdbcTemplate jdbc, MeterRegistry metrics) {
    this.jdbc = jdbc;
    quirks = QuirkRegister.load();
    untriagedGauge = metrics.gauge("pas.parallel_run.untriaged", Tags.empty(), new AtomicLong());
    bugGauge = metrics.gauge("pas.parallel_run.bugs", Tags.empty(), new AtomicLong());
  }

  @Transactional
  public ClassificationResult classify(
      long differenceId, String classification, String quirkId, String note, String operator) {
    if (operator == null || operator.isBlank()) {
      throw new IllegalArgumentException("Operator identity is required");
    }
    Classification next;
    try {
      next = Classification.valueOf(classification);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Unsupported difference classification");
    }
    if (next == Classification.APPROVED_FIX && (quirkId == null || !quirks.approvesFix(quirkId))) {
      throw new IllegalArgumentException(
          "APPROVED_FIX requires a quirk whose decision contains Fix");
    }
    if ((next == Classification.BUG || next == Classification.DATA_ISSUE)
        && (note == null || note.isBlank())) {
      throw new IllegalArgumentException("BUG and DATA_ISSUE require a nonblank note");
    }
    UUID cycleId =
        jdbc.queryForObject(
            "SELECT cycle_id FROM pas_batch.parallel_run_difference WHERE id = ?",
            UUID.class,
            differenceId);
    int updated =
        jdbc.update(
            """
            UPDATE pas_batch.parallel_run_difference
            SET classification = ?, quirk_id = ?, triage_note = ?, triaged_by = ?, triaged_at = ?
            WHERE id = ? AND present
            """,
            next.name(),
            quirkId,
            blankToNull(note),
            operator,
            OffsetDateTime.now(),
            differenceId);
    if (updated != 1) {
      throw new IllegalArgumentException("Difference was not found or is no longer present");
    }
    jdbc.update(
        """
        INSERT INTO pas_batch.parallel_run_triage_log
            (difference_id, classification, quirk_id, triage_note, triaged_by, triaged_at)
        VALUES (?, ?, ?, ?, ?, now())
        """,
        differenceId,
        next.name(),
        quirkId,
        blankToNull(note),
        operator);
    if (cycleId != null) {
      refreshCycleStatus(cycleId);
    }
    updateParallelRunGauges();
    return new ClassificationResult(differenceId, next.name(), quirkId, operator);
  }

  void updateParallelRunGauges() {
    untriagedGauge.set(countDifferences("UNTRIAGED"));
    bugGauge.set(countDifferences("BUG"));
  }

  private long countDifferences(String classification) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pas_batch.parallel_run_difference
            WHERE present AND classification = ?
            """,
            Long.class,
            classification);
    return count == null ? 0 : count;
  }

  private void refreshCycleStatus(UUID cycleId) {
    var range =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT start_date, end_date FROM pas_batch.parallel_run_cycle WHERE cycle_id = ?",
                (resultSet, rowNum) ->
                    new java.time.LocalDate[] {
                      resultSet.getDate(1).toLocalDate(), resultSet.getDate(2).toLocalDate()
                    },
                cycleId),
            "Parallel-run cycle was not found");
    long expected = java.time.temporal.ChronoUnit.DAYS.between(range[0], range[1]) + 1;
    Long days =
        jdbc.queryForObject(
            "SELECT count(*) FROM pas_batch.parallel_run_date WHERE cycle_id = ?",
            Long.class,
            cycleId);
    Long unresolved =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pas_batch.parallel_run_difference
            WHERE cycle_id = ? AND present AND classification IN ('UNTRIAGED', 'BUG')
            """,
            Long.class,
            cycleId);
    String status =
        ParallelRunService.cycleStatus(
            expected,
            java.util.Objects.requireNonNullElse(days, 0L),
            java.util.Objects.requireNonNullElse(unresolved, 0L));
    jdbc.update(
        "UPDATE pas_batch.parallel_run_cycle SET status = ? WHERE cycle_id = ?", status, cycleId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public enum Classification {
    UNTRIAGED,
    BUG,
    APPROVED_FIX,
    DATA_ISSUE
  }

  public record ClassificationResult(
      long differenceId, String classification, String quirkId, String operator) {}
}
