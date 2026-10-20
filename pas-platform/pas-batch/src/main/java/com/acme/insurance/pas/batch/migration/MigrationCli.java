package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.report.CheckReport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot CLI profile of {@code pas-batch} ({@code --pas.migration.cli=bulk-load,delta-sync,
 * reconcile,parallel-run,pause-sync,resume-sync}). Used by {@code make reconcile}, the rollback
 * rehearsal and operators. Exits 0 when every check passed, 3 when a check had unexplained
 * differences or was incomplete, 1 on failure.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
@Component
@ConditionalOnProperty(name = "pas.migration.cli")
public class MigrationCli implements ApplicationRunner {

  static final int EXIT_CHECK_FAILED = 3;
  private static final Logger LOG = LoggerFactory.getLogger(MigrationCli.class);

  private final ConfigurableApplicationContext context;
  private final JobLauncher launcher;
  private final Job bulkLoad;
  private final DeltaSyncService delta;
  private final MigrationChecks checks;
  private final SyncState syncState;
  private final List<String> commands;
  private final LocalDate businessDate;
  private final boolean exit;

  public MigrationCli(
      ConfigurableApplicationContext context,
      JobLauncher launcher,
      @Qualifier("db2BulkLoadJob") Job bulkLoad,
      DeltaSyncService delta,
      MigrationChecks checks,
      SyncState syncState,
      @Value("${pas.migration.cli}") String commands,
      @Value("${pas.migration.business-date:}") String businessDate,
      @Value("${pas.migration.cli-exit:true}") boolean exit) {
    this.context = context;
    this.launcher = launcher;
    this.bulkLoad = bulkLoad;
    this.delta = delta;
    this.checks = checks;
    this.syncState = syncState;
    this.commands = Arrays.stream(commands.split(",")).map(String::strip).toList();
    this.businessDate =
        businessDate == null || businessDate.isBlank()
            ? LocalDate.now()
            : LocalDate.parse(businessDate);
    this.exit = exit;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    int code = 0;
    for (String command : commands) {
      code = Math.max(code, execute(command));
    }
    LOG.info("migration cli {} finished exitCode={}", commands, code);
    if (exit) {
      terminate(code);
    }
  }

  int execute(String command) throws Exception {
    String runId = "cli-" + command + "-" + System.currentTimeMillis();
    switch (command) {
      case "bulk-load" -> {
        JobExecution execution =
            launcher.run(
                bulkLoad,
                new JobParametersBuilder()
                    .addLocalDateTime("launchedAt", LocalDateTime.now())
                    .toJobParameters());
        return execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
      }
      case "delta-sync" -> {
        delta.sync(runId);
        return 0;
      }
      case "reconcile" -> {
        return checkCode(checks.reconcile(businessDate, runId));
      }
      case "parallel-run" -> {
        return checkCode(checks.parallelRun(businessDate, runId));
      }
      case "pause-sync" -> {
        syncState.setPaused(true, "rollback: pointing back at DB2", "migration-cli");
        return 0;
      }
      case "resume-sync" -> {
        syncState.setPaused(false, null, "migration-cli");
        return 0;
      }
      default -> throw new IllegalArgumentException("unknown pas.migration.cli command " + command);
    }
  }

  private static int checkCode(CheckReport report) {
    return report.passed() ? 0 : EXIT_CHECK_FAILED;
  }

  @SuppressFBWarnings(value = "DM_EXIT", justification = "one-shot CLI profile must set exit code")
  private void terminate(int code) {
    System.exit(SpringApplication.exit(context, () -> code));
  }
}
