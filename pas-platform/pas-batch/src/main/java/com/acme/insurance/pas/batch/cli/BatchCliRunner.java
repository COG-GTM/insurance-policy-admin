package com.acme.insurance.pas.batch.cli;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pas.batch.cli.job")
public class BatchCliRunner implements CommandLineRunner, ExitCodeGenerator {
  private static final Logger LOGGER = LoggerFactory.getLogger(BatchCliRunner.class);

  private final JobLauncher jobLauncher;
  private final Map<String, Job> jobs;
  private final RouteFlags routes;
  private final String jobName;
  private final String requestedDate;
  private final String rerun;
  private final String cycleId;
  private final String drain;
  private final ZoneId premiumZone;
  private final ZoneId extractZone;
  private int exitCode = 1;

  public BatchCliRunner(
      JobLauncher jobLauncher,
      Map<String, Job> jobs,
      RouteFlags routes,
      @Value("${pas.batch.cli.job}") String jobName,
      @Value("${pas.batch.cli.business-date:}") String requestedDate,
      @Value("${pas.batch.cli.rerun:}") String rerun,
      @Value("${pas.batch.cli.cycle-id:}") String cycleId,
      @Value("${pas.batch.cli.drain:true}") String drain,
      @Value("${pas.batch.premium.zone:America/New_York}") String premiumZone,
      @Value("${pas.batch.extract.zone:America/New_York}") String extractZone) {
    this.jobLauncher = jobLauncher;
    this.jobs = Map.copyOf(jobs);
    this.routes = routes;
    this.jobName = jobName;
    this.requestedDate = requestedDate;
    this.rerun = rerun;
    this.cycleId = cycleId;
    this.drain = drain;
    this.premiumZone = ZoneId.of(premiumZone);
    this.extractZone = ZoneId.of(extractZone);
  }

  @Override
  @SuppressFBWarnings(
      value = "REC_CATCH_EXCEPTION",
      justification = "CLI failures are converted to a nonzero process exit code.")
  public void run(String... args) {
    Job job = jobs.get(jobName);
    if (job == null) {
      LOGGER.warn("Batch job {} is not registered", jobName);
      exitCode = 2;
      return;
    }
    try {
      JobParametersBuilder builder = new JobParametersBuilder();
      if (jobName.equals("premiumRecalculationJob")
          || jobName.equals("dailyExtractJob")
          || jobName.equals("monthlyExposureJob")
          || jobName.equals("parallelRunDiffJob")) {
        ZoneId zone = jobName.equals("premiumRecalculationJob") ? premiumZone : extractZone;
        LocalDate businessDate =
            requestedDate.isBlank() ? LocalDate.now(zone) : LocalDate.parse(requestedDate);
        builder.addString("businessDate", businessDate.toString(), true);
      }
      if (isMigrationRunIdJob(jobName) || jobName.equals("bulkLoadJob")) {
        builder.addLong(
            "runId", rerun.isBlank() ? System.currentTimeMillis() : Long.parseLong(rerun), true);
      }
      if (jobName.equals("parallelRunDiffJob") && !cycleId.isBlank()) {
        builder.addString("cycleId", cycleId, true);
      }
      if (jobName.equals("reverseSyncJob")) {
        builder.addString("drain", drain, false);
      }
      if (!rerun.isBlank()) {
        builder.addLong("rerun", Long.parseLong(rerun), true);
      }
      JobParameters parameters = builder.toJobParameters();
      LOGGER.info("Launching batch job {} with parameters {}", jobName, parameters);
      JobExecution execution = jobLauncher.run(job, parameters);
      exitCode = exitCode(jobName, execution, routes);
      LOGGER.info(
          "Batch job {} finished with status {}, exit status {}, CLI exit code {}",
          jobName,
          execution.getStatus(),
          execution.getExitStatus().getExitCode(),
          exitCode);
    } catch (Exception exception) {
      LOGGER.error("Batch job {} failed to launch or complete", jobName, exception);
      exitCode = 1;
    }
  }

  static int exitCode(String jobName, JobExecution execution, RouteFlags routes) {
    if ("DISABLED".equals(execution.getExitStatus().getExitCode())) {
      return 3;
    }
    if ("COMPLETED_WITH_ERRORS".equals(execution.getExitStatus().getExitCode())) {
      return 4;
    }
    if ("COMPLETED_WITH_DIFFERENCES".equals(execution.getExitStatus().getExitCode())) {
      return 5;
    }
    if (isRetired(jobName, routes)) {
      return 1;
    }
    return execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
  }

  private static boolean isMigrationRunIdJob(String jobName) {
    return jobName.equals("deltaSyncJob")
        || jobName.equals("reverseSyncJob")
        || jobName.equals("reconciliationJob");
  }

  private static boolean isRetired(String jobName, RouteFlags routes) {
    return switch (jobName) {
      case "dailyExtractJob" -> routes.state(RouteFlags.DAILY_EXTRACT) == RouteState.RETIRED;
      case "monthlyExposureJob" -> routes.state(RouteFlags.MONTHLY_EXPOSURE) == RouteState.RETIRED;
      default -> false;
    };
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
