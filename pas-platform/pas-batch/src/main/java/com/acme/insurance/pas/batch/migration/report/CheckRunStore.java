package com.acme.insurance.pas.batch.migration.report;

import com.acme.insurance.pas.batch.migration.triage.Classification;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists check summaries to {@code pas.migration_check_runs} (Flyway V7). */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class CheckRunStore {

  private final JdbcTemplate jdbc;

  public CheckRunStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(CheckReport r, ReportWriter.Written files, String summaryJson) {
    jdbc.update(
        "INSERT INTO pas.migration_check_runs (run_id, check_type, business_date, started_at,"
            + " finished_at, status, differences, bug_count, approved_fix_count,"
            + " data_issue_count, unclassified_count, unexplained_count, report_json_path,"
            + " report_md_path, summary_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.runId(),
        r.checkType().name(),
        Date.valueOf(r.businessDate()),
        Timestamp.valueOf(r.startedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()),
        Timestamp.valueOf(r.finishedAt().atOffset(ZoneOffset.UTC).toLocalDateTime()),
        r.status(),
        r.differences().size(),
        r.count(Classification.BUG),
        r.count(Classification.APPROVED_FIX),
        r.count(Classification.DATA_ISSUE),
        r.count(Classification.UNCLASSIFIED),
        r.unexplained(),
        files.json().toString(),
        files.markdown().toString(),
        summaryJson);
  }

  /** Latest summary of a check type (no record bodies), for the actuator endpoint and gauges. */
  public Optional<Map<String, Object>> latest(CheckType type) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT run_id, business_date, finished_at, status, differences, bug_count,"
                + " approved_fix_count, data_issue_count, unclassified_count,"
                + " unexplained_count, report_md_path FROM pas.migration_check_runs"
                + " WHERE check_type = ? ORDER BY finished_at DESC LIMIT 1",
            type.name());
    return rows.isEmpty() ? Optional.empty() : Optional.of(new LinkedHashMap<>(rows.get(0)));
  }

  /**
   * Consecutive most-recent business dates whose latest run passed, used for the "full cycle"
   * criterion. A calendar day with no recorded run breaks the streak like a failed day.
   */
  public int consecutivePassingDays(CheckType type) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT business_date, status FROM (SELECT DISTINCT ON (business_date) business_date,"
                + " status FROM pas.migration_check_runs WHERE check_type = ?"
                + " ORDER BY business_date DESC, finished_at DESC) latest_per_day"
                + " ORDER BY business_date DESC",
            type.name());
    int days = 0;
    java.time.LocalDate expected = null;
    for (Map<String, Object> row : rows) {
      java.time.LocalDate date = ((Date) row.get("business_date")).toLocalDate();
      if (!"PASS".equals(row.get("status")) || (expected != null && !date.equals(expected))) {
        break;
      }
      days++;
      expected = date.minusDays(1);
    }
    return days;
  }
}
