package com.acme.insurance.pas.batch.migration.parallel;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/batch/migration")
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Spring-managed collaborators are retained for the lifetime of this controller.")
public class ParallelRunController {
  private final JdbcTemplate jdbc;
  private final TriageService triageService;

  public ParallelRunController(JdbcTemplate jdbc, TriageService triageService) {
    this.jdbc = jdbc;
    this.triageService = triageService;
  }

  @PostMapping("/parallel-run/cycles")
  @Transactional
  public ResponseEntity<Map<String, Object>> createCycle(
      @RequestBody CreateCycleRequest request, JwtAuthenticationToken authentication) {
    if (request.name() == null
        || request.name().isBlank()
        || request.startDate() == null
        || request.endDate() == null
        || request.endDate().isBefore(request.startDate())) {
      throw new IllegalArgumentException("Cycle name and valid date range are required");
    }
    UUID cycleId = UUID.randomUUID();
    String operator = authentication.getToken().getSubject();
    jdbc.update(
        """
        INSERT INTO pas_batch.parallel_run_cycle
            (cycle_id, name, start_date, end_date, status, created_by)
        VALUES (?, ?, ?, ?, 'RUNNING', ?)
        """,
        cycleId,
        request.name().trim(),
        request.startDate(),
        request.endDate(),
        operator);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "cycleId", cycleId,
                "name", request.name().trim(),
                "startDate", request.startDate(),
                "endDate", request.endDate(),
                "status", "RUNNING"));
  }

  @GetMapping("/parallel-run/cycles")
  public List<Map<String, Object>> cycles() {
    return jdbc.queryForList(
        """
        SELECT cycle_id, name, start_date, end_date, status, created_by, created_at
        FROM pas_batch.parallel_run_cycle
        ORDER BY created_at DESC
        """);
  }

  @GetMapping("/parallel-run/cycles/{cycleId}")
  public Map<String, Object> cycle(@PathVariable UUID cycleId) {
    Map<String, Object> cycle =
        jdbc.queryForMap(
            """
            SELECT cycle_id, name, start_date, end_date, status, created_by, created_at
            FROM pas_batch.parallel_run_cycle WHERE cycle_id = ?
            """,
            cycleId);
    cycle.put(
        "dates",
        jdbc.queryForList(
            """
            SELECT business_date, last_run_at, counts
            FROM pas_batch.parallel_run_date WHERE cycle_id = ?
            ORDER BY business_date
            """,
            cycleId));
    return cycle;
  }

  @GetMapping("/parallel-run/differences")
  public List<Map<String, Object>> differences(
      @RequestParam(required = false) LocalDate businessDate,
      @RequestParam(required = false) String classification) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, cycle_id, business_date, artifact, record_key, field, legacy_value,
                   shadow_value, classification, quirk_id, triage_note, triaged_by, triaged_at
            FROM pas_batch.parallel_run_difference
            WHERE present
            """);
    java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
    if (businessDate != null) {
      sql.append(" AND business_date = ?");
      parameters.add(businessDate);
    }
    if (classification != null && !classification.isBlank()) {
      sql.append(" AND classification = ?");
      parameters.add(classification);
    }
    sql.append(" ORDER BY business_date DESC, artifact, record_key, field");
    return jdbc.queryForList(sql.toString(), parameters.toArray());
  }

  @PostMapping("/parallel-run/differences/{differenceId}/classification")
  public TriageService.ClassificationResult classify(
      @PathVariable long differenceId,
      @RequestBody TriageRequest request,
      JwtAuthenticationToken authentication) {
    return triageService.classify(
        differenceId,
        request.classification(),
        request.quirkId(),
        request.note(),
        authentication.getToken().getSubject());
  }

  @GetMapping("/recon/runs/latest")
  public Map<String, Object> latestReconciliation() {
    return jdbc.queryForMap(
        """
        SELECT id, started_at, finished_at, status, counts
        FROM pas_batch.recon_run ORDER BY started_at DESC FETCH FIRST 1 ROWS ONLY
        """);
  }

  public record CreateCycleRequest(String name, LocalDate startDate, LocalDate endDate) {}

  public record TriageRequest(String classification, String quirkId, String note) {}
}

@RestControllerAdvice(assignableTypes = ParallelRunController.class)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
class ParallelRunControllerErrors {
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
  }
}
