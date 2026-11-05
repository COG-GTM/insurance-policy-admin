package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.ActivePolicy;
import com.acme.insurance.pas.persistence.PremiumRunJdbcRepository;
import com.acme.insurance.pas.persistence.PremiumRunTotals;
import com.acme.insurance.pas.rating.PremiumRater;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Job {@code premiumRecalculation} (AB-523), the replacement of PREMIUM-BATCH.jcl / PREMBAT (CA-7
 * PAS0050). Step {@code premiumRating} splits the active book into policy-number ranges and rates
 * them in parallel, {@code chunk-size} policies per transaction, against one rating snapshot per
 * run. Step {@code premiumReport} writes the run report from the recorded outcomes and stores the
 * READ / UPDATED / ERRORS totals in the job execution context. Restarting a failed run resumes the
 * unfinished ranges from their last committed chunk.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PremiumProperties.class)
public final class PremiumJobConfiguration {

  public static final String JOB = "premiumRecalculation";
  public static final String RATING_STEP = "premiumRating";
  public static final String PARTITION_STEP = "premiumRatingPartition";
  public static final String REPORT_STEP = "premiumReport";
  public static final String READ = "premium.read";
  public static final String UPDATED = "premium.updated";
  public static final String ERRORS = "premium.errors";
  public static final String REPORT_FILE = "premium.reportFile";

  private static final Logger log = LoggerFactory.getLogger(PremiumJobConfiguration.class);
  private static final PlatformTransactionManager NO_TX = new ResourcelessTransactionManager();

  private final PremiumProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  public PremiumJobConfiguration(
      PremiumProperties properties,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<Clock> clock) {
    this.properties = properties;
    this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @Bean
  PremiumRunJdbcRepository premiumRunJdbcRepository(JdbcTemplate jdbc) {
    return new PremiumRunJdbcRepository(jdbc);
  }

  @Bean
  PremiumRunRaters premiumRunRaters(PremiumRater premiumRater) {
    return new PremiumRunRaters(premiumRater);
  }

  @Bean
  Job premiumRecalculation(
      JobRepository jobs,
      PremiumRunRaters raters,
      PremiumRunJdbcRepository repository,
      Step premiumRatingPartition) {
    PremiumJobParameters parameters =
        new PremiumJobParameters(
            clock,
            properties.zone(),
            () -> properties.reportDir() == null ? "pas.premium.report-dir is not set" : null);
    return new JobBuilder(JOB, jobs)
        .incrementer(parameters)
        .validator(parameters)
        .listener(raters)
        .listener(new PremiumJobAlerting(meters))
        .start(ratingStep(jobs, repository, premiumRatingPartition))
        .next(reportStep(jobs, repository))
        .build();
  }

  @Bean
  Step premiumRatingPartition(
      JobRepository jobs,
      PlatformTransactionManager transactionManager,
      JdbcPagingItemReader<ActivePolicy> premiumPolicyReader,
      PremiumItemProcessor premiumItemProcessor,
      PremiumItemWriter premiumItemWriter,
      PremiumSkipRecorder premiumSkipRecorder) {
    return new StepBuilder(PARTITION_STEP, jobs)
        .<ActivePolicy, PremiumOutcome>chunk(properties.chunkSize(), transactionManager)
        .reader(premiumPolicyReader)
        .processor(premiumItemProcessor)
        .writer(premiumItemWriter)
        .faultTolerant()
        .skip(DataIntegrityViolationException.class)
        .skipLimit(properties.skipLimit())
        .listener(premiumSkipRecorder)
        .build();
  }

  @Bean
  @StepScope
  JdbcPagingItemReader<ActivePolicy> premiumPolicyReader(
      DataSource dataSource,
      @Value("#{stepExecutionContext['" + PolicyRangePartitioner.FROM + "']}") String from,
      @Value("#{stepExecutionContext['" + PolicyRangePartitioner.TO + "']}") String to) {
    StringBuilder where = new StringBuilder(PremiumRunJdbcRepository.ACTIVE_POLICY_FILTER);
    Map<String, Object> values = new HashMap<>();
    if (from != null) {
      where.append(" AND policy_number >= :fromPolicy");
      values.put("fromPolicy", from);
    }
    if (to != null) {
      where.append(" AND policy_number < :toPolicy");
      values.put("toPolicy", to);
    }
    return new JdbcPagingItemReaderBuilder<ActivePolicy>()
        .name("premiumPolicyReader")
        .dataSource(dataSource)
        .selectClause(PremiumRunJdbcRepository.ACTIVE_POLICY_COLUMNS)
        .fromClause(PremiumRunJdbcRepository.ACTIVE_POLICY_TABLE)
        .whereClause(where.toString())
        .sortKeys(Map.of(PremiumRunJdbcRepository.SORT_KEY, Order.ASCENDING))
        .parameterValues(values)
        .pageSize(properties.chunkSize())
        .rowMapper(PremiumRunJdbcRepository.ACTIVE_POLICY)
        .build();
  }

  @Bean
  @StepScope
  PremiumItemProcessor premiumItemProcessor(
      PremiumRunRaters raters, @Value("#{stepExecution}") StepExecution step) {
    return new PremiumItemProcessor(
        raters.forExecution(step.getJobExecutionId()), businessDate(step.getJobExecution()));
  }

  @Bean
  @StepScope
  PremiumItemWriter premiumItemWriter(
      PremiumRunJdbcRepository repository, @Value("#{stepExecution}") StepExecution step) {
    return new PremiumItemWriter(
        repository, runId(step.getJobExecution()), businessDate(step.getJobExecution()));
  }

  @Bean
  @StepScope
  PremiumSkipRecorder premiumSkipRecorder(
      PremiumRunJdbcRepository repository,
      PlatformTransactionManager transactionManager,
      @Value("#{stepExecution}") StepExecution step) {
    return new PremiumSkipRecorder(
        repository,
        transactionManager,
        runId(step.getJobExecution()),
        businessDate(step.getJobExecution()));
  }

  private Step ratingStep(JobRepository jobs, PremiumRunJdbcRepository repository, Step worker) {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("premium-partition-");
    executor.setConcurrencyLimit(properties.partitions());
    return new StepBuilder(RATING_STEP, jobs)
        .partitioner(PARTITION_STEP, new PolicyRangePartitioner(repository))
        .step(worker)
        .gridSize(properties.partitions())
        .taskExecutor(executor)
        .build();
  }

  private Step reportStep(JobRepository jobs, PremiumRunJdbcRepository repository) {
    Tasklet tasklet =
        (contribution, chunk) -> {
          JobExecution job = chunk.getStepContext().getStepExecution().getJobExecution();
          LocalDate businessDate = businessDate(job);
          long runId = runId(job);
          PremiumRunTotals totals = repository.totals(runId);
          Path report =
              PremiumReport.write(
                  properties.reportDir(),
                  businessDate,
                  sink -> repository.streamOutcomes(runId, sink),
                  totals);
          ExecutionContext context = job.getExecutionContext();
          context.putLong(READ, totals.read());
          context.putLong(UPDATED, totals.updated());
          context.putLong(ERRORS, totals.errors());
          context.putString(REPORT_FILE, report.toString());
          contribution.incrementWriteCount(totals.read());
          meters.counter("pas.premium.policies", "outcome", "updated").increment(totals.updated());
          meters.counter("pas.premium.policies", "outcome", "error").increment(totals.errors());
          log.info(
              "pas.premium.summary businessDate={} run={} read={} updated={} errors={} report={}",
              businessDate,
              runId,
              totals.read(),
              totals.updated(),
              totals.errors(),
              report.getFileName());
          return RepeatStatus.FINISHED;
        };
    return new StepBuilder(REPORT_STEP, jobs)
        .tasklet(tasklet, NO_TX)
        .allowStartIfComplete(true)
        .build();
  }

  private static LocalDate businessDate(JobExecution job) {
    return PremiumJobParameters.businessDate(job.getJobParameters());
  }

  private static long runId(JobExecution job) {
    return job.getJobInstance().getInstanceId();
  }
}
