package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-shot CLI (container / Kubernetes Job / external scheduler): runs the job synchronously and
 * exits 0 (completed), 4 (completed with errors) or 16 (failed), PREMBAT's return codes.
 *
 * <pre>
 * java -jar pas-batch.jar --spring.main.web-application-type=none \
 *   --pas.batch.premium.cli.run=true [--pas.batch.premium.cli.run-date=2026-10-20] \
 *   [--pas.batch.premium.cli.rerun=true | --pas.batch.premium.cli.restart-execution-id=42]
 * </pre>
 *
 * Access is controlled by who may run the container (platform RBAC); the HTTP API uses {@code
 * batch:operate}.
 */
@Component
@ConditionalOnProperty(name = "pas.batch.premium.cli.run", havingValue = "true")
public class PremiumJobCli implements ApplicationRunner, ExitCodeGenerator {

  private static final Logger log = LoggerFactory.getLogger(PremiumJobCli.class);

  private final PremiumJobLauncher launcher;
  private final List<BatchAlertNotifier> notifiers;
  private final ZoneId zone;
  private final String runDate;
  private final boolean rerun;
  private final Long restartExecutionId;
  private int exitCode = PremiumRunOutcome.FAILED.exitCode();

  public PremiumJobCli(
      PremiumJobLauncher launcher,
      ObjectProvider<BatchAlertNotifier> notifiers,
      PremiumJobProperties props,
      @Value("${pas.batch.premium.cli.run-date:}") String runDate,
      @Value("${pas.batch.premium.cli.rerun:false}") boolean rerun,
      @Value("${pas.batch.premium.cli.restart-execution-id:#{null}}") Long restartExecutionId) {
    this.launcher = launcher;
    this.notifiers = notifiers.orderedStream().toList();
    this.zone = ZoneId.of(props.zone());
    this.runDate = runDate;
    this.rerun = rerun;
    this.restartExecutionId = restartExecutionId;
  }

  @Override
  public void run(ApplicationArguments args) {
    LocalDate date = null;
    try {
      date = runDate.isBlank() ? LocalDate.now(zone) : LocalDate.parse(runDate);
      JobExecution execution =
          restartExecutionId != null
              ? launcher.restart(restartExecutionId, false)
              : launcher.start(date, rerun, false);
      exitCode = PremiumRunOutcome.of(execution).exitCode();
    } catch (JobInstanceAlreadyCompleteException e) {
      // report the outcome of the run that already completed (4 if it had write errors)
      exitCode =
          launcher
              .lastExecution(date)
              .map(PremiumRunOutcome::of)
              .orElse(PremiumRunOutcome.COMPLETED)
              .exitCode();
      log.info(
          "Premium run for {} already completed (exit {}); use rerun to recalculate",
          date,
          exitCode);
    } catch (Exception e) {
      PremiumAlerts.send(notifiers, PremiumAlerts.launchFailed(date, e));
      exitCode = PremiumRunOutcome.FAILED.exitCode();
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
