package com.acme.insurance.pas.batch;

import com.acme.insurance.pas.batch.migration.MigrationJobsConfiguration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.batch.JobExecutionEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Process exit code of a pas-batch run, which decides whether the Kubernetes Job succeeds. Replaces
 * Spring Boot's {@code JobExecutionExitCodeGenerator} (it backs off for any {@link
 * ExitCodeGenerator} bean) and keeps its rule: a job that did not complete exits with its {@link
 * BatchStatus} ordinal (FAILED = 5). A completed job exits 0 unless its exit status is listed in
 * {@link #COMPLETED_EXIT_CODES}: AB-529 reconciliation differences exit {@value #DIFFERENCES}, so
 * the Job fails and pages. The premium job's {@code COMPLETED_WITH_ERRORS} stays 0 (a warning,
 * AB-523).
 */
@Component
public class BatchExitCodeGenerator
    implements ApplicationListener<JobExecutionEvent>, ExitCodeGenerator {

  public static final int DIFFERENCES = 10;

  static final Map<String, Integer> COMPLETED_EXIT_CODES =
      Map.of(MigrationJobsConfiguration.DIFFERENCES, DIFFERENCES);

  private final List<JobExecution> executions = new CopyOnWriteArrayList<>();

  @Override
  public void onApplicationEvent(JobExecutionEvent event) {
    executions.add(event.getJobExecution());
  }

  @Override
  public int getExitCode() {
    for (JobExecution execution : executions) {
      if (execution.getStatus() != BatchStatus.COMPLETED) {
        return execution.getStatus().ordinal();
      }
      Integer code = COMPLETED_EXIT_CODES.get(execution.getExitStatus().getExitCode());
      if (code != null) {
        return code;
      }
    }
    return 0;
  }
}
