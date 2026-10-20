package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.parallelrun.ParallelRunService;
import com.acme.insurance.pas.batch.migration.reconcile.ReconciliationService;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.report.CheckRunStore;
import com.acme.insurance.pas.batch.migration.report.MigrationMetrics;
import com.acme.insurance.pas.batch.migration.report.ReportWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a check, writes its JSON/Markdown reports, records it and updates the gauges. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class MigrationChecks {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationChecks.class);

  private final ReconciliationService reconciliation;
  private final ParallelRunService parallelRun;
  private final ReportWriter writer;
  private final CheckRunStore store;
  private final MigrationMetrics metrics;

  public MigrationChecks(
      ReconciliationService reconciliation,
      ParallelRunService parallelRun,
      ReportWriter writer,
      CheckRunStore store,
      MigrationMetrics metrics) {
    this.reconciliation = reconciliation;
    this.parallelRun = parallelRun;
    this.writer = writer;
    this.store = store;
    this.metrics = metrics;
  }

  public CheckReport reconcile(LocalDate businessDate, String runId) {
    return publish(reconciliation.reconcile(businessDate, runId));
  }

  public CheckReport parallelRun(LocalDate businessDate, String runId) {
    return publish(parallelRun.run(businessDate, runId));
  }

  private CheckReport publish(CheckReport report) {
    ReportWriter.Written files = writer.write(report);
    store.record(report, files, summary(report));
    metrics.update(report);
    LOG.info(
        "{} {} businessDate={} status={} unexplained={} counts={} report={}",
        report.checkType(),
        report.runId(),
        report.businessDate(),
        report.status(),
        report.unexplained(),
        report.classificationCounts(),
        files.markdown());
    return report;
  }

  private String summary(CheckReport report) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("status", report.status());
    summary.put("unexplained", report.unexplained());
    summary.put("classificationCounts", report.classificationCounts());
    summary.put("sections", report.sections());
    try {
      return writer.mapper().writeValueAsString(summary);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
