package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.batch.alert.BatchAlertNotifier;
import com.acme.insurance.pas.batch.alert.JobFailureAlertListener;
import com.acme.insurance.pas.batch.extract.ExtractOutputs;
import com.acme.insurance.pas.batch.extract.SingleRunPerDateTasklet;
import com.acme.insurance.pas.common.domain.PremiumRecord;
import com.acme.insurance.pas.rating.PremiumRatingService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.support.CompositeItemWriter;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code premiumRecalculationJob}: Spring Batch replacement of {@code jcl/PREMIUM-BATCH.jcl}
 * (PREMBAT, CA-7 PAS0050).
 *
 * <pre>
 * premiumSingleRunGuard   fail if an earlier run for the same business date is still running
 * premiumReportInitStep   create the report directory; fail the run if it cannot (Q-25)
 * premiumRecalculationStep partition the active book by policy-number range, then per partition:
 *   premiumWorkerStep      JDBC paging read (status 'AC', policy-number order) -> rate with the
 *                          shared AB-522 component -> upsert PREMIUMS + report part file
 * premiumReportStep       assemble PREMRPT with READ / UPDATED / ERRORS totals
 * </pre>
 *
 * Job parameters: {@code businessDate} (identifying; one job instance per business date, rating
 * as-of date and CALC_DATE) and optionally {@code rerun} (identifying; a new instance that
 * recalculates a date that already completed). Relaunching a failed instance restarts it from the
 * last committed chunk of each unfinished partition.
 */
@Configuration
@EnableConfigurationProperties(PremiumJobProperties.class)
public class PremiumRecalculationJobConfig {

  public static final String JOB_NAME = "premiumRecalculationJob";

  /** Same parameter name as the AB-527 extract jobs, so {@link SingleRunPerDateTasklet} applies. */
  public static final String RUN_DATE = ExtractOutputs.BUSINESS_DATE;

  public static final String RERUN = "rerun";
  static final String WORKER_STEP = "premiumWorkerStep";

  private static final Logger log = LoggerFactory.getLogger(PremiumRecalculationJobConfig.class);

  @Bean
  public RatingSnapshotRegistry ratingSnapshotRegistry(PremiumRatingService rating) {
    return new RatingSnapshotRegistry(rating);
  }

  @Bean
  public PremiumRunSummaryRepository premiumRunSummaryRepository(DataSource dataSource) {
    return new PremiumRunSummaryRepository(dataSource);
  }

  @Bean
  public PremiumJobExecutionListener premiumJobExecutionListener(
      RatingSnapshotRegistry snapshots,
      JobExplorer explorer,
      PremiumRunSummaryRepository summaries,
      ObjectProvider<BatchAlertNotifier> notifiers,
      MeterRegistry meters) {
    return new PremiumJobExecutionListener(
        snapshots, explorer, summaries, notifiers.orderedStream().toList(), meters);
  }

  @Bean
  public ActivePolicyPartitioner activePolicyPartitioner(DataSource dataSource) {
    return new ActivePolicyPartitioner(dataSource);
  }

  @Bean
  @StepScope
  public JdbcPagingItemReader<ActivePolicy> activePolicyReader(
      DataSource dataSource,
      PremiumJobProperties props,
      @Value("#{stepExecutionContext['" + ActivePolicyPartitioner.MIN_POLICY + "']}") String min,
      @Value("#{stepExecutionContext['" + ActivePolicyPartitioner.MAX_POLICY + "']}") String max) {
    PostgresPagingQueryProvider query = new PostgresPagingQueryProvider();
    query.setSelectClause("policy_number, policy_type, effective_date, expiry_date");
    query.setFromClause("pas.policies");
    // PREMBAT POL_CURSOR: WHERE POLICY_STATUS = 'AC' ORDER BY POLICY_NUMBER
    query.setWhereClause(
        "policy_status = 'AC'"
            + " AND policy_number BETWEEN CAST(:minPolicy AS CHAR(12)) AND CAST(:maxPolicy AS CHAR(12))");
    query.setSortKeys(Map.of("policy_number", Order.ASCENDING));
    return new JdbcPagingItemReaderBuilder<ActivePolicy>()
        .name("activePolicyReader")
        .dataSource(dataSource)
        .queryProvider(query)
        .parameterValues(Map.of("minPolicy", min, "maxPolicy", max))
        .pageSize(props.chunkSize())
        .fetchSize(props.chunkSize())
        .rowMapper(
            (rs, i) ->
                new ActivePolicy(
                    rs.getString("policy_number"),
                    rs.getString("policy_type"),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getObject("expiry_date", LocalDate.class)))
        .build();
  }

  @Bean
  @StepScope
  public PremiumRatingProcessor premiumRatingProcessor(
      PremiumRatingService rating,
      RatingSnapshotRegistry snapshots,
      @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
    return new PremiumRatingProcessor(rating, snapshots.get(jobExecutionId));
  }

  @Bean
  public PremiumUpsertWriter premiumUpsertWriter(DataSource dataSource) {
    return new PremiumUpsertWriter(dataSource);
  }

  @Bean
  @StepScope
  public FlatFileItemWriter<PremiumRecord> premiumReportPartWriter(
      PremiumJobProperties props,
      @Value("#{stepExecution.jobExecution.jobInstance.instanceId}") Long jobInstanceId,
      @Value("#{stepExecutionContext['" + ActivePolicyPartitioner.PARTITION_NAME + "']}")
          String partition) {
    return new FlatFileItemWriterBuilder<PremiumRecord>()
        .name("premiumReportPartWriter")
        .resource(
            new FileSystemResource(
                PremiumRunReport.partFile(props.reportDir(), jobInstanceId, partition)))
        .lineAggregator(PremiumRunReport::detailLine)
        .transactional(true)
        .build();
  }

  @Bean
  @StepScope
  public CompositeItemWriter<PremiumRecord> premiumWriter(
      PremiumUpsertWriter upsert, FlatFileItemWriter<PremiumRecord> premiumReportPartWriter) {
    return new CompositeItemWriter<>(List.of(upsert, premiumReportPartWriter));
  }

  @Bean
  public Step premiumWorkerStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      PremiumJobProperties props,
      JdbcPagingItemReader<ActivePolicy> activePolicyReader,
      PremiumRatingProcessor premiumRatingProcessor,
      CompositeItemWriter<PremiumRecord> premiumWriter) {
    return new StepBuilder(WORKER_STEP, jobRepository)
        .<ActivePolicy, PremiumRecord>chunk(props.chunkSize(), transactionManager)
        .reader(activePolicyReader)
        .processor(premiumRatingProcessor)
        .writer(premiumWriter)
        .faultTolerant()
        // PREMBAT counts a failed INSERT as an error and carries on (3300, RC 4)
        .skip(DataIntegrityViolationException.class)
        .skipLimit(props.skipLimit())
        .listener(
            new SkipListener<ActivePolicy, PremiumRecord>() {
              @Override
              public void onSkipInWrite(PremiumRecord item, Throwable t) {
                log.warn(
                    "PREMIUMS write failed policy={} termEffective={}: {}",
                    item.policyNumber(),
                    item.termEffectiveDate(),
                    t.getMessage());
              }
            })
        .build();
  }

  @Bean
  public Step premiumRecalculationStep(
      JobRepository jobRepository,
      PremiumJobProperties props,
      ActivePolicyPartitioner activePolicyPartitioner,
      Step premiumWorkerStep) {
    SimpleAsyncTaskExecutor partitionThreads = new SimpleAsyncTaskExecutor("premium-partition-");
    partitionThreads.setConcurrencyLimit(props.partitions());
    return new StepBuilder("premiumRecalculationStep", jobRepository)
        .partitioner(WORKER_STEP, activePolicyPartitioner)
        .step(premiumWorkerStep)
        .gridSize(props.partitions())
        .taskExecutor(partitionThreads)
        .build();
  }

  @Bean
  public Step premiumReportInitStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      PremiumJobProperties props) {
    return new StepBuilder("premiumReportInitStep", jobRepository)
        .tasklet(
            (contribution, chunk) -> {
              long instanceId =
                  contribution
                      .getStepExecution()
                      .getJobExecution()
                      .getJobInstance()
                      .getInstanceId();
              Path runDir = PremiumRunReport.runDir(props.reportDir(), instanceId);
              Files.createDirectories(runDir);
              if (!Files.isWritable(runDir)) {
                throw new IllegalStateException("premium report directory not writable: " + runDir);
              }
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .allowStartIfComplete(true)
        .build();
  }

  @Bean
  public Step premiumReportStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      PremiumJobProperties props,
      JobExplorer explorer) {
    return new StepBuilder("premiumReportStep", jobRepository)
        .tasklet(
            (contribution, chunk) -> {
              var jobExecution = contribution.getStepExecution().getJobExecution();
              PremiumRunTotals totals =
                  PremiumRunTotals.of(explorer, jobExecution.getJobInstance());
              Path report =
                  PremiumRunReport.write(
                      props.reportDir(),
                      PremiumJobExecutionListener.runDate(jobExecution),
                      jobExecution.getJobInstance().getInstanceId(),
                      totals);
              jobExecution
                  .getExecutionContext()
                  .putString(PremiumJobExecutionListener.REPORT_PATH_KEY, report.toString());
              if (totals.errors() > 0) {
                contribution.setExitStatus(PremiumRunOutcome.COMPLETED_WITH_ERRORS_STATUS);
              }
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .allowStartIfComplete(true)
        .build();
  }

  @Bean
  public Job premiumRecalculationJob(
      JobRepository jobRepository,
      PremiumJobExecutionListener premiumJobExecutionListener,
      JobFailureAlertListener alertListener,
      JobExplorer jobExplorer,
      PlatformTransactionManager transactionManager,
      Step premiumReportInitStep,
      Step premiumRecalculationStep,
      Step premiumReportStep) {
    return new JobBuilder(JOB_NAME, jobRepository)
        .listener(premiumJobExecutionListener)
        .listener(alertListener)
        .start(
            new StepBuilder("premiumSingleRunGuard", jobRepository)
                .tasklet(new SingleRunPerDateTasklet(jobExplorer), transactionManager)
                .allowStartIfComplete(true)
                .build())
        .next(premiumReportInitStep)
        .next(premiumRecalculationStep)
        .next(premiumReportStep)
        .build();
  }
}
