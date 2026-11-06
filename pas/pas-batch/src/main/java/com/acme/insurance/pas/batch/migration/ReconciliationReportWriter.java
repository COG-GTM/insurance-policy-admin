package com.acme.insurance.pas.batch.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the daily reconciliation report as JSON (for dashboards and the parallel-run tool) and
 * Markdown (posted for the team). Only keys, dimensions and totals appear; never row values.
 */
public class ReconciliationReportWriter {

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path dir;

  public ReconciliationReportWriter(Path dir) {
    this.dir = dir;
  }

  /** Returns the JSON file written, or null when no report directory is configured. */
  public Path write(long runId, String jobName, ReconciliationReport report) {
    if (dir == null) {
      return null;
    }
    String base = "reconciliation-%s-run%d".formatted(report.businessDate(), runId);
    try {
      Files.createDirectories(dir);
      Path json = dir.resolve(base + ".json");
      Files.writeString(json, JSON.writeValueAsString(toMap(runId, jobName, report)));
      Files.writeString(
          dir.resolve(base + ".md"), markdown(runId, jobName, report), StandardCharsets.UTF_8);
      return json;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write reconciliation report", e);
    }
  }

  static Map<String, Object> toMap(long runId, String jobName, ReconciliationReport report) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runId", runId);
    out.put("job", jobName);
    out.put("businessDate", report.businessDate().toString());
    out.put("status", report.matches() ? "MATCH" : "DIFFERENCES");
    out.put("checks", report.checks().size());
    out.put("failedChecks", report.failedChecks());
    out.put("recordDifferences", report.differenceCount());
    out.put(
        "failed",
        report.checks().stream()
            .filter(c -> !c.matches())
            .map(
                c -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("check", c.id());
                  m.put("db2", c.db2Value() == null ? null : c.db2Value().toPlainString());
                  m.put("pg", c.pgValue() == null ? null : c.pgValue().toPlainString());
                  return m;
                })
            .toList());
    out.put(
        "differences",
        report.differences().stream()
            .map(
                d ->
                    Map.of(
                        "table", d.table().name(), "key", d.recordKey(), "kind", d.kind().name()))
            .toList());
    return out;
  }

  static String markdown(long runId, String jobName, ReconciliationReport report) {
    StringBuilder md = new StringBuilder();
    md.append("# PAS DB2 / PostgreSQL reconciliation ")
        .append(report.businessDate())
        .append("\n\n");
    md.append("- Run: ").append(runId).append(" (").append(jobName).append(")\n");
    md.append("- Result: **").append(report.matches() ? "MATCH" : "DIFFERENCES").append("**\n");
    md.append("- Checks: ").append(report.checks().size()).append(", failed: ");
    md.append(report.failedChecks()).append("\n");
    md.append("- Record differences: ").append(report.differenceCount()).append("\n\n");
    md.append("| Check | DB2 | PostgreSQL | Status |\n|---|---:|---:|---|\n");
    List<ReconciliationReport.Check> shown =
        report.checks().stream()
            .filter(c -> !c.matches() || c.kind() != ReconciliationReport.Kind.TOTAL)
            .toList();
    for (ReconciliationReport.Check c : shown) {
      md.append("| ").append(c.id()).append(" | ").append(plain(c.db2Value()));
      md.append(" | ").append(plain(c.pgValue())).append(" | ");
      md.append(c.matches() ? "MATCH" : "**DIFF**").append(" |\n");
    }
    long totals =
        report.checks().stream().filter(c -> c.kind() == ReconciliationReport.Kind.TOTAL).count();
    md.append("\nPolicy-type/branch totals compared: ").append(totals);
    md.append(" (only differing totals are listed above).\n");
    if (!report.differences().isEmpty()) {
      md.append("\n## Record differences (first ").append(report.differences().size());
      md.append(")\n\n| Table | Key | Difference |\n|---|---|---|\n");
      report
          .differences()
          .forEach(
              d ->
                  md.append("| ")
                      .append(d.table())
                      .append(" | ")
                      .append(d.recordKey())
                      .append(" | ")
                      .append(d.kind())
                      .append(" |\n"));
    }
    return md.toString();
  }

  private static String plain(java.math.BigDecimal value) {
    return value == null ? "-" : value.toPlainString();
  }
}
