package com.acme.insurance.pas.batch.premium;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One premium run as recorded in {@code pas_batch.premium_run_summary} and logged.
 *
 * @param reportPath PREMRPT report file, null when the run failed before writing it
 * @param ratingVersion rate version / factor set used, null if the tables could not be loaded
 * @param failureMessage first failure of the execution, null when it completed
 */
public record PremiumRunSummary(
    long jobExecutionId,
    long jobInstanceId,
    LocalDate runDate,
    String batchStatus,
    String exitCode,
    PremiumRunOutcome outcome,
    PremiumRunTotals totals,
    String ratingVersion,
    String reportPath,
    LocalDateTime startedAt,
    LocalDateTime endedAt,
    String failureMessage) {}
