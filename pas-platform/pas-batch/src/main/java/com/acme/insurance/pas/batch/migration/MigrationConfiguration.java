package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.exposure.ExposureCsv;
import com.acme.insurance.pas.batch.exposure.MonthlyExposureJobConfig;
import com.acme.insurance.pas.batch.extract.DailyExtractJobConfig;
import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import com.acme.insurance.pas.batch.extract.ExtractRecordLayouts;
import com.acme.insurance.pas.batch.migration.parallelrun.JdbcPremiumOutputSource;
import com.acme.insurance.pas.batch.migration.parallelrun.LineFileAdapter;
import com.acme.insurance.pas.batch.migration.parallelrun.OutputAdapter;
import com.acme.insurance.pas.batch.migration.parallelrun.ParallelRunService;
import com.acme.insurance.pas.batch.migration.parallelrun.PremiumRowsAdapter;
import com.acme.insurance.pas.batch.migration.reconcile.ReconciliationService;
import com.acme.insurance.pas.batch.migration.report.CheckRunStore;
import com.acme.insurance.pas.batch.migration.report.MigrationMetrics;
import com.acme.insurance.pas.batch.migration.report.MigrationStatusEndpoint;
import com.acme.insurance.pas.batch.migration.report.ReportWriter;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import com.acme.insurance.pas.common.pii.BlindIndexer;
import com.acme.insurance.pas.common.pii.PiiCipher;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AB-529 wiring inside the existing {@code pas-batch} deployable (ADR-0001: one policy service +
 * one batch app). Off unless {@code pas.migration.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationConfiguration {

  public static final String DELTA_JOB = "db2DeltaSyncJob";
  public static final String RECONCILE_JOB = "migrationReconciliationJob";
  public static final String PARALLEL_RUN_JOB = "parallelRunDiffJob";
  private static final int KEY_BYTES = 32;

  /** POLICY_TYPE, BRANCH_CODE, COVERAGE_TYPE, RATING_TERRITORY, CLASS_CODE. */
  private static final int EXPOSURE_GROUP_COLUMNS = 5;

  @Bean(destroyMethod = "close")
  SourceDatabase migrationSource(MigrationProperties properties) {
    return new SourceDatabase(properties.source());
  }

  @Bean
  PolicyHolderPii policyHolderPii(
      @Value("${pas.pii.encryption-key:}") String encryptionKey,
      @Value("${pas.pii.blind-index-key:}") String blindIndexKey,
      @Value("${pas.pii.key-id:k1}") String keyId) {
    byte[] enc = decode("pas.pii.encryption-key", encryptionKey);
    byte[] idx = decode("pas.pii.blind-index-key", blindIndexKey);
    if (MessageDigest.isEqual(enc, idx)) {
      throw new IllegalStateException("pas.pii.encryption-key and blind-index-key must differ");
    }
    String id = keyId == null || keyId.isBlank() ? "k1" : keyId.strip();
    return new PolicyHolderPii(new PiiCipher(enc, id), new BlindIndexer(idx));
  }

  @Bean
  TargetWriter migrationTargetWriter(NamedParameterJdbcTemplate jdbc, PolicyHolderPii pii) {
    return new TargetWriter(jdbc, pii);
  }

  @Bean
  SyncState migrationSyncState(JdbcTemplate jdbc) {
    return new SyncState(jdbc);
  }

  @Bean
  DeltaSyncService deltaSyncService(
      SourceDatabase source,
      TargetWriter writer,
      SyncState state,
      PlatformTransactionManager tx,
      MigrationProperties properties) {
    return new DeltaSyncService(source, writer, state, new TransactionTemplate(tx), properties);
  }

  @Bean
  TriageRegister triageRegister(MigrationProperties properties) throws IOException {
    return TriageRegister.load(properties.triageRegister(), properties.quirkRegister());
  }

  @Bean
  ReconciliationService reconciliationService(
      SourceDatabase source,
      JdbcTemplate jdbc,
      TriageRegister triage,
      MigrationProperties properties) {
    return new ReconciliationService(
        source, jdbc, triage, properties.sampleLimit(), Clock.systemUTC());
  }

  @Bean
  ParallelRunService parallelRunService(
      SourceDatabase source,
      JdbcTemplate jdbc,
      ExtractOutputs outputs,
      TriageRegister triage,
      MigrationProperties properties,
      ObjectProvider<OutputAdapter> extraAdapters) {
    var legacyDir = properties.legacyOutputDir();
    List<OutputAdapter> adapters = new ArrayList<>();
    adapters.add(
        LineFileAdapter.fixedWidth(
            DailyExtractJobConfig.JOB_NAME,
            DailyExtractJobConfig.POLICY_FILE,
            legacyDir,
            outputs,
            date -> true,
            ExtractRecordLayouts.POLICY_EXTRACT,
            "POLICY_NUMBER"));
    adapters.add(
        LineFileAdapter.fixedWidth(
            DailyExtractJobConfig.JOB_NAME,
            DailyExtractJobConfig.COVERAGE_FILE,
            legacyDir,
            outputs,
            date -> true,
            ExtractRecordLayouts.COVERAGE_EXTRACT,
            "POLICY_NUMBER",
            "SEQUENCE_NUM"));
    adapters.add(
        LineFileAdapter.csv(
            MonthlyExposureJobConfig.JOB_NAME,
            MonthlyExposureJobConfig.EXPOSURE_FILE,
            legacyDir,
            outputs,
            (LocalDate date) -> date.getDayOfMonth() == 1,
            ExposureCsv.COLUMNS,
            EXPOSURE_GROUP_COLUMNS));
    adapters.add(
        new PremiumRowsAdapter(
            JdbcPremiumOutputSource.legacy(source), JdbcPremiumOutputSource.shadow(jdbc)));
    extraAdapters.orderedStream().forEach(adapters::add);
    List<String> provenance =
        List.of(
            "legacy extracts: "
                + legacyDir
                + "/<job>/<business-date>/ (mainframe captures;"
                + " parity oracle goldens when run locally/CI — not mainframe output)",
            "legacy premiums: " + source.table("PREMIUMS") + " rows with CALC_DATE = business date",
            "new: AB-527 extract job outputs and pas_shadow.premiums (AB-523 shadow mode)");
    return new ParallelRunService(adapters, triage, provenance, Clock.systemUTC());
  }

  @Bean
  ReportWriter migrationReportWriter(MigrationProperties properties) {
    return new ReportWriter(properties.reportDir());
  }

  @Bean
  CheckRunStore checkRunStore(JdbcTemplate jdbc) {
    return new CheckRunStore(jdbc);
  }

  @Bean
  MigrationMetrics migrationMetrics(MeterRegistry registry) {
    return new MigrationMetrics(registry);
  }

  @Bean
  MigrationStatusEndpoint migrationStatusEndpoint(
      CheckRunStore store, SyncState state, JdbcTemplate jdbc) {
    return new MigrationStatusEndpoint(store, state, jdbc);
  }

  @Bean
  MigrationChecks migrationChecks(
      ReconciliationService reconciliation,
      ParallelRunService parallelRun,
      ReportWriter writer,
      CheckRunStore store,
      MigrationMetrics metrics) {
    return new MigrationChecks(reconciliation, parallelRun, writer, store, metrics);
  }

  @Bean
  Job db2DeltaSyncJob(
      JobRepository repository, PlatformTransactionManager tx, DeltaSyncService delta) {
    Step step =
        new StepBuilder(DELTA_JOB + ".sync", repository)
            .tasklet(
                (contribution, chunk) -> {
                  long id = chunk.getStepContext().getStepExecution().getJobExecutionId();
                  delta.sync(DELTA_JOB + "-" + id);
                  return RepeatStatus.FINISHED;
                },
                tx)
            .build();
    return new JobBuilder(DELTA_JOB, repository).start(step).build();
  }

  @Bean
  Job migrationReconciliationJob(
      JobRepository repository, PlatformTransactionManager tx, MigrationChecks checks) {
    return checkJob(RECONCILE_JOB, repository, tx, checks, false);
  }

  @Bean
  Job parallelRunDiffJob(
      JobRepository repository, PlatformTransactionManager tx, MigrationChecks checks) {
    return checkJob(PARALLEL_RUN_JOB, repository, tx, checks, true);
  }

  private static Job checkJob(
      String name,
      JobRepository repository,
      PlatformTransactionManager tx,
      MigrationChecks checks,
      boolean parallelRun) {
    Step step =
        new StepBuilder(name + ".check", repository)
            .tasklet(
                (contribution, chunk) -> {
                  LocalDate date = ExtractOutputs.businessDate(chunk);
                  String runId =
                      name + "-" + chunk.getStepContext().getStepExecution().getJobExecutionId();
                  var report =
                      parallelRun ? checks.parallelRun(date, runId) : checks.reconcile(date, runId);
                  contribution.setExitStatus(
                      new org.springframework.batch.core.ExitStatus(report.status()));
                  return RepeatStatus.FINISHED;
                },
                tx)
            .build();
    return new JobBuilder(name, repository).start(step).build();
  }

  private static byte[] decode(String property, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is not set (needed to encrypt holder PII)");
    }
    try {
      byte[] key = Base64.getDecoder().decode(value.strip());
      if (key.length != KEY_BYTES) {
        throw new IllegalStateException(property + " must be a Base64-encoded 256-bit key");
      }
      return key;
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(property + " is not valid Base64", e);
    }
  }
}
