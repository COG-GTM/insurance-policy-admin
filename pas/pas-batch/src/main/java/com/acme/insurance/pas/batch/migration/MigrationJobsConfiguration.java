package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.MigrationConnections.Side;
import com.acme.insurance.pas.batch.migration.MigrationRunStore.Direction;
import com.acme.insurance.pas.batch.migration.TableSynchronizer.Phase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * AB-529 jobs that move ACMEINS from DB2 to PostgreSQL and prove the two agree:
 *
 * <ul>
 *   <li>{@code migrationBulkLoad}: initial load into an empty PostgreSQL ACMEINS, POLICY_SEQ moved
 *       above the DB2 high-water mark, then a full reconciliation.
 *   <li>{@code migrationDeltaSync}: the scheduled change sync while both systems run (until the
 *       log-based CDC product of ADR 0001 D3 is licensed, FU-529-1), then a reconciliation.
 *   <li>{@code migrationReconciliation}: the daily reconciliation on its own.
 *   <li>{@code migrationReverseSync}: rollback, PostgreSQL back into DB2 after a cutover, DB2
 *       POLICY_SEQ moved above PostgreSQL, reconciliation, and DB2 restored as system of record.
 *   <li>{@code migrationSystemOfRecord}: records the cutover (PG) or rollback (DB2) decision that
 *       the sync guards read.
 * </ul>
 *
 * Results go to acmeins_shadow and to JSON/Markdown reports; differences raise {@code
 * pas.migration.alert}. Row values are never logged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
public final class MigrationJobsConfiguration {

  public static final String BULK_LOAD_JOB = "migrationBulkLoad";
  public static final String DELTA_SYNC_JOB = "migrationDeltaSync";
  public static final String RECONCILIATION_JOB = "migrationReconciliation";
  public static final String REVERSE_SYNC_JOB = "migrationReverseSync";
  public static final String SYSTEM_OF_RECORD_JOB = "migrationSystemOfRecord";

  static final String RUN_ID_KEY = "migration.runId";
  static final String RESULT_KEY = "migration.result";
  static final String DIFFERENCES = "DIFFERENCES";
  static final String COLLAPSE_SAME_DAY_DECISIONS = "collapseSameDayDecisions";
  private static final String SAME_DAY_DECISIONS =
      "SELECT COUNT(*) FROM (SELECT POLICY_NUMBER, DECISION_DATE FROM"
          + " ACMEINS.UNDERWRITING_DECISIONS GROUP BY POLICY_NUMBER, DECISION_DATE"
          + " HAVING COUNT(*) > 1) D";

  private static final Logger log = LoggerFactory.getLogger(MigrationJobsConfiguration.class);
  private static final PlatformTransactionManager NO_TX = new ResourcelessTransactionManager();

  private final MigrationProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  public MigrationJobsConfiguration(
      MigrationProperties properties,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<Clock> clock) {
    this.properties = properties;
    this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @Bean
  MigrationConnections migrationConnections() {
    return new MigrationConnections(properties);
  }

  @Bean
  Job migrationBulkLoad(JobRepository jobs, MigrationConnections connections) {
    return syncJob(jobs, connections, BULK_LOAD_JOB, Direction.DB2_TO_PG, true);
  }

  @Bean
  Job migrationDeltaSync(JobRepository jobs, MigrationConnections connections) {
    return syncJob(jobs, connections, DELTA_SYNC_JOB, Direction.DB2_TO_PG, false);
  }

  @Bean
  Job migrationReverseSync(JobRepository jobs, MigrationConnections connections) {
    return syncJob(jobs, connections, REVERSE_SYNC_JOB, Direction.PG_TO_DB2, false);
  }

  @Bean
  Job migrationReconciliation(JobRepository jobs, MigrationConnections connections) {
    return new JobBuilder(RECONCILIATION_JOB, jobs)
        .incrementer(parameters())
        .listener(new RunListener(connections, Direction.NONE))
        .start(reconcileStep(jobs, connections, RECONCILIATION_JOB, false))
        .build();
  }

  @Bean
  Job migrationSystemOfRecord(
      JobRepository jobs, MigrationConnections connections, JobExplorer explorer) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          var params = chunk.getStepContext().getStepExecution().getJobParameters();
          String system = params.getString("system", "").toUpperCase(Locale.ROOT);
          if (!List.of("PG", "DB2").contains(system)
              || !"YES".equals(params.getString("confirm"))) {
            throw new IllegalArgumentException("pass system=PG|DB2 and confirm=YES");
          }
          String reason = params.getString("reason", "");
          String by = params.getString("changedBy", "");
          if (reason.isBlank() || by.isBlank()) {
            throw new IllegalArgumentException("pass reason= and changedBy= for the audit trail");
          }
          for (String sync : List.of(BULK_LOAD_JOB, DELTA_SYNC_JOB, REVERSE_SYNC_JOB)) {
            if (!explorer.findRunningJobExecutions(sync).isEmpty()) {
              throw new IllegalStateException(
                  sync
                      + " is running; suspend the sync CronJob and let the run finish (or mark an"
                      + " abandoned execution) before changing the system of record");
            }
          }
          store(connections).setSystemOfRecord(system, by, reason);
          log.warn("pas.migration.system-of-record changed to={} by={}", system, by);
          return RepeatStatus.FINISHED;
        };
    return new JobBuilder(SYSTEM_OF_RECORD_JOB, jobs)
        .incrementer(parameters())
        .start(new StepBuilder("systemOfRecord", jobs).tasklet(tasklet, NO_TX).build())
        .build();
  }

  private MigrationParameters parameters() {
    return new MigrationParameters(clock, properties.zone());
  }

  private Job syncJob(
      JobRepository jobs,
      MigrationConnections connections,
      String name,
      Direction direction,
      boolean bulkLoad) {
    boolean reverse = direction == Direction.PG_TO_DB2;
    SimpleJobBuilder builder =
        new JobBuilder(name, jobs)
            .incrementer(parameters())
            .listener(new RunListener(connections, direction))
            .start(preflightStep(jobs, connections, direction, bulkLoad));
    for (MigrationTable table : MigrationTable.values()) {
      builder.next(syncStep(jobs, connections, direction, table, Phase.UPSERT));
    }
    List<MigrationTable> childFirst = new ArrayList<>(Arrays.asList(MigrationTable.values()));
    java.util.Collections.reverse(childFirst);
    for (MigrationTable table : childFirst) {
      builder.next(syncStep(jobs, connections, direction, table, Phase.DELETE));
    }
    builder.next(sequenceStep(jobs, connections, direction));
    builder.next(reconcileStep(jobs, connections, name, reverse));
    return builder.build();
  }

  private Step preflightStep(
      JobRepository jobs, MigrationConnections connections, Direction direction, boolean bulkLoad) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          var params = chunk.getStepContext().getStepExecution().getJobParameters();
          String system = store(connections).systemOfRecord();
          if (direction == Direction.DB2_TO_PG && !"DB2".equals(system)) {
            throw new IllegalStateException(
                "system of record is " + system + "; a DB2 -> PostgreSQL sync would overwrite it");
          }
          if (direction == Direction.PG_TO_DB2) {
            if (!"PG".equals(system)) {
              throw new IllegalStateException(
                  "system of record is " + system + "; nothing to roll back");
            }
            if (!"YES".equals(params.getString("confirmRollback"))) {
              throw new IllegalArgumentException("a rollback needs confirmRollback=YES");
            }
            Long sameDay =
                new JdbcTemplate(connections.dataSource(Side.POSTGRES))
                    .queryForObject(SAME_DAY_DECISIONS, Long.class);
            if (sameDay != null
                && sameDay > 0
                && !"YES".equals(params.getString(COLLAPSE_SAME_DAY_DECISIONS))) {
              throw new IllegalStateException(
                  ("PostgreSQL has %d policy/date pairs with several underwriting decisions; DB2"
                          + " keeps one per day (Q-18). Export them from acmeins.underwriting_decisions"
                          + " (they stay on PostgreSQL) and pass %s=YES to send the most recent"
                          + " decision of each day to DB2")
                      .formatted(sameDay, COLLAPSE_SAME_DAY_DECISIONS));
            }
          }
          if (bulkLoad && !"true".equals(params.getString("resume"))) {
            JdbcTemplate pg = new JdbcTemplate(connections.dataSource(Side.POSTGRES));
            for (MigrationTable table : MigrationTable.values()) {
              Long rows =
                  pg.queryForObject("SELECT COUNT(*) FROM " + table.qualifiedName(), Long.class);
              if (rows != null && rows > 0) {
                throw new IllegalStateException(
                    "PostgreSQL %s is not empty; the bulk load needs an empty target (resume=true to continue a load)"
                        .formatted(table.qualifiedName()));
              }
            }
          }
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder("preflight", jobs).tasklet(tasklet, NO_TX).build();
  }

  private Step syncStep(
      JobRepository jobs,
      MigrationConnections connections,
      Direction direction,
      MigrationTable table,
      Phase phase) {
    Side from = direction == Direction.PG_TO_DB2 ? Side.POSTGRES : Side.DB2;
    Side to = direction == Direction.PG_TO_DB2 ? Side.DB2 : Side.POSTGRES;
    Tasklet tasklet =
        (contribution, chunk) -> {
          TableSynchronizer synchronizer =
              new TableSynchronizer(properties.bucketDigits(), properties.batchSize());
          var params = chunk.getStepContext().getStepExecution().getJobParameters();
          String authoritative = direction == Direction.PG_TO_DB2 ? "PG" : "DB2";
          MigrationRunStore guardStore = store(connections);
          Runnable guard =
              () -> {
                String system = guardStore.systemOfRecord();
                if (!authoritative.equals(system)) {
                  throw new IllegalStateException(
                      "system of record changed to " + system + " during the sync; stopped");
                }
              };
          boolean collapse =
              direction == Direction.PG_TO_DB2
                  && table == MigrationTable.UNDERWRITING_DECISIONS
                  && "YES".equals(params.getString(COLLAPSE_SAME_DAY_DECISIONS));
          TableSynchronizer.Result result =
              synchronizer.sync(
                  table,
                  phase,
                  connections.client(from),
                  new JdbcTemplate(connections.dataSource(to)),
                  guard,
                  collapse);
          long runId = runId(chunk.getStepContext().getStepExecution().getJobExecution());
          store(connections).recordSync(runId, table, result);
          contribution.incrementWriteCount(result.inserted() + result.updated() + result.deleted());
          meters
              .counter("pas.migration.rows", "table", table.name(), "direction", direction.name())
              .increment(result.inserted() + result.updated() + result.deleted());
          log.info(
              "migration sync table={} phase={} direction={} sourceRows={} inserted={} updated={}"
                  + " deleted={}",
              table,
              phase,
              direction,
              result.sourceRows(),
              result.inserted(),
              result.updated(),
              result.deleted());
          return RepeatStatus.FINISHED;
        };
    String name = "sync" + (phase == Phase.UPSERT ? "Upsert" : "Delete") + "-" + table.name();
    return new StepBuilder(name, jobs).tasklet(tasklet, NO_TX).build();
  }

  private Step sequenceStep(
      JobRepository jobs, MigrationConnections connections, Direction direction) {
    Side from = direction == Direction.PG_TO_DB2 ? Side.POSTGRES : Side.DB2;
    Side to = direction == Direction.PG_TO_DB2 ? Side.DB2 : Side.POSTGRES;
    Tasklet tasklet =
        (contribution, chunk) -> {
          SequenceAligner.Alignment alignment =
              SequenceAligner.align(
                  connections.client(from),
                  connections.vendor(from),
                  connections.client(to),
                  connections.vendor(to),
                  properties.sequenceGap());
          log.info(
              "migration POLICY_SEQ aligned direction={} sendingHighWater={} receivingBefore={}"
                  + " receivingNext={}",
              direction,
              alignment.sendingHighWater(),
              alignment.receivingBefore(),
              alignment.receivingNext());
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder("alignPolicySequence", jobs).tasklet(tasklet, NO_TX).build();
  }

  private Step reconcileStep(
      JobRepository jobs, MigrationConnections connections, String jobName, boolean rollback) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          JobExecution job = chunk.getStepContext().getStepExecution().getJobExecution();
          LocalDate businessDate = MigrationParameters.businessDate(job.getJobParameters());
          ReconciliationReport report =
              new Reconciler(properties.bucketDigits(), properties.hashTables())
                  .reconcile(
                      businessDate,
                      connections.client(Side.DB2),
                      connections.client(Side.POSTGRES));
          long runId = runId(job);
          MigrationRunStore store = store(connections);
          store.recordReconciliation(runId, report);
          Path json =
              new ReconciliationReportWriter(properties.reportDir()).write(runId, jobName, report);
          job.getExecutionContext().putString(RESULT_KEY, report.matches() ? "MATCH" : DIFFERENCES);
          meters.gauge(
              "pas.migration.reconciliation.differences",
              List.of(io.micrometer.core.instrument.Tag.of("job", jobName)),
              new java.util.concurrent.atomic.AtomicLong(
                  report.differenceCount() + report.failedChecks()));
          log.info(
              "migration reconciliation businessDate={} runId={} checks={} failedChecks={}"
                  + " recordDifferences={} report={}",
              businessDate,
              runId,
              report.checks().size(),
              report.failedChecks(),
              report.differenceCount(),
              json);
          if (rollback) {
            if (!report.matches()) {
              throw new IllegalStateException(
                  "rollback reconciliation found differences; DB2 stays read-only, see run "
                      + runId);
            }
            store.setSystemOfRecord("DB2", REVERSE_SYNC_JOB, "rollback reconciled, run " + runId);
            log.warn("pas.migration.system-of-record changed to=DB2 by={}", REVERSE_SYNC_JOB);
          }
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder("reconcile", jobs).tasklet(tasklet, NO_TX).build();
  }

  private static long runId(JobExecution job) {
    return job.getExecutionContext().getLong(RUN_ID_KEY);
  }

  private static MigrationRunStore store(MigrationConnections connections) {
    return new MigrationRunStore(connections.client(Side.POSTGRES));
  }

  /** Opens and closes the acmeins_shadow run row and raises {@code pas.migration.alert}. */
  private final class RunListener implements JobExecutionListener {

    private final MigrationConnections connections;
    private final Direction direction;

    RunListener(MigrationConnections connections, Direction direction) {
      this.connections = connections;
      this.direction = direction;
    }

    @Override
    public void beforeJob(JobExecution execution) {
      ExecutionContext context = execution.getExecutionContext();
      MigrationRunStore store = store(connections);
      if (context.containsKey(RUN_ID_KEY)) {
        store.restart(context.getLong(RUN_ID_KEY), execution.getId());
        return;
      }
      long runId =
          store.start(
              execution.getJobInstance().getJobName(),
              execution.getId(),
              direction,
              MigrationParameters.businessDate(execution.getJobParameters()));
      context.putLong(RUN_ID_KEY, runId);
    }

    @Override
    public void afterJob(JobExecution execution) {
      String job = execution.getJobInstance().getJobName();
      ExecutionContext context = execution.getExecutionContext();
      String result =
          execution.getStatus() != BatchStatus.COMPLETED
              ? "FAILED"
              : context.getString(RESULT_KEY, direction == Direction.NONE ? "MATCH" : "SYNCED");
      if (context.containsKey(RUN_ID_KEY)) {
        try {
          store(connections).finish(context.getLong(RUN_ID_KEY), result);
        } catch (RuntimeException e) {
          log.error("cannot close migration run job={}: {}", job, e.getClass().getSimpleName());
        }
      }
      if ("MATCH".equals(result) || "SYNCED".equals(result)) {
        meters.counter("pas.migration.job.success", "job", job).increment();
        return;
      }
      if (DIFFERENCES.equals(result)) {
        execution.setExitStatus(new ExitStatus(DIFFERENCES, "reconciliation found differences"));
      }
      meters.counter("pas.migration.job.failures", "job", job, "result", result).increment();
      String reasons =
          execution.getAllFailureExceptions().stream()
              .map(MigrationJobsConfiguration::alertReason)
              .toList()
              .toString();
      log.error(
          "pas.migration.alert job={} result={} status={} reasons={}",
          job,
          result,
          execution.getStatus(),
          reasons);
    }
  }

  /**
   * A failure reason that is safe to log: the guard messages this package throws (they carry counts
   * and job names only) or, for anything else, the SQLSTATE, vendor code (DB2 SQLCODE) and root
   * class. Driver message text is never logged because it can echo row values ("Failing row
   * contains ...").
   */
  static String alertReason(Throwable t) {
    if ((t instanceof IllegalStateException || t instanceof IllegalArgumentException)
        && t.getCause() == null
        && t.getStackTrace().length > 0
        && t.getStackTrace()[0]
            .getClassName()
            .startsWith(MigrationJobsConfiguration.class.getPackageName())) {
      return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
    Throwable root = t;
    java.sql.SQLException sql = null;
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      if (c instanceof java.sql.SQLException e && e.getSQLState() != null) {
        sql = e;
      }
      root = c;
    }
    String rootClass = root.getClass().getSimpleName();
    if (sql == null) {
      return t.getClass().getSimpleName() + " (" + rootClass + ")";
    }
    String vendor = sql.getErrorCode() == 0 ? "" : " vendor code " + sql.getErrorCode();
    return "SQLSTATE " + sql.getSQLState() + vendor + " (" + rootClass + ")";
  }
}
