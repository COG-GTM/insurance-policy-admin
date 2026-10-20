package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator API for the premium run. Every path requires scope {@code batch:operate} (see
 * BatchSecurityConfig). Launches are asynchronous: poll {@code GET /runs/{id}}.
 */
@RestController
@RequestMapping("/api/batch/premium/runs")
public class PremiumJobController {

  /** Status of one execution; totals are cumulative over the instance's restarts. */
  public record RunView(
      long jobExecutionId,
      long jobInstanceId,
      LocalDate runDate,
      String batchStatus,
      String exitCode,
      PremiumRunOutcome outcome,
      PremiumRunTotals totals,
      String reportPath) {}

  private final PremiumJobLauncher launcher;
  private final JobExplorer explorer;
  private final PremiumRunSummaryRepository summaries;
  private final ZoneId zone;

  public PremiumJobController(
      PremiumJobLauncher launcher,
      JobExplorer explorer,
      PremiumRunSummaryRepository summaries,
      PremiumJobProperties props) {
    this.launcher = launcher;
    this.explorer = explorer;
    this.summaries = summaries;
    this.zone = ZoneId.of(props.zone());
  }

  @PostMapping
  public ResponseEntity<RunView> start(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate runDate,
      @RequestParam(defaultValue = "false") boolean rerun)
      throws Exception {
    LocalDate date = runDate != null ? runDate : LocalDate.now(zone);
    return ResponseEntity.accepted().body(view(launcher.start(date, rerun, true)));
  }

  @PostMapping("/{jobExecutionId}/restart")
  public ResponseEntity<RunView> restart(@PathVariable long jobExecutionId) throws Exception {
    if (launcher.find(jobExecutionId).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.accepted().body(view(launcher.restart(jobExecutionId, true)));
  }

  @GetMapping("/{jobExecutionId}")
  public ResponseEntity<RunView> get(@PathVariable long jobExecutionId) {
    Optional<JobExecution> execution = launcher.find(jobExecutionId);
    return execution.map(e -> ResponseEntity.ok(view(e))).orElse(ResponseEntity.notFound().build());
  }

  private RunView view(JobExecution e) {
    String report = summaries.find(e.getId()).map(PremiumRunSummary::reportPath).orElse(null);
    PremiumRunOutcome outcome = e.getStatus().isRunning() ? null : PremiumRunOutcome.of(e);
    return new RunView(
        e.getId(),
        e.getJobInstance().getInstanceId(),
        e.getJobParameters().getLocalDate(PremiumRecalculationJobConfig.RUN_DATE),
        e.getStatus().name(),
        e.getExitStatus().getExitCode(),
        outcome,
        PremiumRunTotals.of(explorer, e.getJobInstance()),
        report);
  }

  @ExceptionHandler({
    JobInstanceAlreadyCompleteException.class,
    JobExecutionAlreadyRunningException.class,
    JobRestartException.class
  })
  ResponseEntity<ApiError> conflict(Exception e, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError.of("PREMIUM_RUN_CONFLICT", e.getMessage(), request.getRequestURI(), null));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(ApiError.of("PREMIUM_RUN_INVALID", e.getMessage(), request.getRequestURI(), null));
  }
}
