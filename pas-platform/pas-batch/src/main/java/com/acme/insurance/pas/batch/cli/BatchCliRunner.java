package com.acme.insurance.pas.batch.cli;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
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
  private final JobLauncher jobLauncher;
  private final Map<String, Job> jobs;
  private final RouteFlags routes;
  private final String jobName;
  private final String requestedDate;
  private final String rerun;
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
      @Value("${pas.batch.premium.zone:America/New_York}") String premiumZone,
      @Value("${pas.batch.extract.zone:America/New_York}") String extractZone) {
    this.jobLauncher = jobLauncher;
    this.jobs = Map.copyOf(jobs);
    this.routes = routes;
    this.jobName = jobName;
    this.requestedDate = requestedDate;
    this.rerun = rerun;
    this.premiumZone = ZoneId.of(premiumZone);
    this.extractZone = ZoneId.of(extractZone);
  }

  @Override
  public void run(String... args) {
    Job job = jobs.get(jobName);
    if (job == null) {
      exitCode = 2;
      return;
    }
    try {
      ZoneId zone = jobName.equals("premiumRecalculationJob") ? premiumZone : extractZone;
      LocalDate businessDate =
          requestedDate.isBlank() ? LocalDate.now(zone) : LocalDate.parse(requestedDate);
      JobParametersBuilder builder =
          new JobParametersBuilder().addString("businessDate", businessDate.toString(), true);
      if (!rerun.isBlank()) {
        builder.addLong("rerun", Long.parseLong(rerun), true);
      }
      JobParameters parameters = builder.toJobParameters();
      JobExecution execution = jobLauncher.run(job, parameters);
      exitCode = exitCode(jobName, execution, routes);
    } catch (Exception exception) {
      exitCode = 1;
    }
  }

  static int exitCode(String jobName, JobExecution execution, RouteFlags routes) {
    if ("premiumRecalculationJob".equals(jobName)) {
      if ("DISABLED".equals(execution.getExitStatus().getExitCode())) {
        return 3;
      }
      if ("COMPLETED_WITH_ERRORS".equals(execution.getExitStatus().getExitCode())) {
        return 4;
      }
    } else if (isRetired(jobName, routes)) {
      return 1;
    }
    return execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
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
