package com.acme.insurance.pas.batch.premium;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public class PremiumReportTasklet implements Tasklet {
  private final JdbcTemplate jdbcTemplate;
  private final LocalDate businessDate;
  private final long jobExecutionId;
  private final long jobInstanceId;
  private final String reportDirectory;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed JDBC template is retained for report queries and writes.")
  public PremiumReportTasklet(
      JdbcTemplate jdbcTemplate,
      LocalDate businessDate,
      long jobExecutionId,
      long jobInstanceId,
      String reportDirectory) {
    this.jdbcTemplate = jdbcTemplate;
    this.businessDate = businessDate;
    this.jobExecutionId = jobExecutionId;
    this.jobInstanceId = jobInstanceId;
    this.reportDirectory = reportDirectory;
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    List<PremiumReportFormatter.PremiumReportItem> items =
        jdbcTemplate.query(
            "SELECT policy_number, status, total_premium, error_type "
                + "FROM pas_batch.premium_run_item WHERE job_instance_id = ? "
                + "ORDER BY policy_number",
            (resultSet, rowNumber) ->
                new PremiumReportFormatter.PremiumReportItem(
                    resultSet.getString("policy_number"),
                    resultSet.getString("status"),
                    resultSet.getBigDecimal("total_premium"),
                    resultSet.getString("error_type")),
            jobInstanceId);
    long updated = items.stream().filter(item -> item.status().equals("UPDATED")).count();
    long errors = items.stream().filter(item -> item.status().equals("ERROR")).count();
    List<String> lines =
        PremiumReportFormatter.format(businessDate, items, items.size(), updated, errors);
    String suffix = businessDate.format(DateTimeFormatter.ofPattern("yyMMdd"));
    Path report =
        Path.of(
            reportDirectory,
            "premiumRecalculationJob",
            businessDate.toString(),
            Long.toString(jobExecutionId),
            "PREMRPT.D" + suffix + ".txt");
    try {
      Path parent = report.getParent();
      if (parent == null) {
        throw new IllegalStateException("Premium report path has no parent directory");
      }
      Files.createDirectories(parent);
      Files.writeString(report, String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to write premium report", exception);
    }
    String target =
        chunkContext
            .getStepContext()
            .getStepExecution()
            .getJobExecution()
            .getExecutionContext()
            .getString("premiumWriteTarget");
    if ("SHADOW".equals(target)) {
      jdbcTemplate.update(
          "DELETE FROM pas_shadow.premium_run_report WHERE business_date = ?", businessDate);
      for (int index = 0; index < lines.size(); index++) {
        jdbcTemplate.update(
            "INSERT INTO pas_shadow.premium_run_report "
                + "(business_date, job_instance_id, line_no, line_text) VALUES (?, ?, ?, ?)",
            businessDate,
            jobInstanceId,
            index + 1,
            lines.get(index));
      }
    }
    return RepeatStatus.FINISHED;
  }
}
