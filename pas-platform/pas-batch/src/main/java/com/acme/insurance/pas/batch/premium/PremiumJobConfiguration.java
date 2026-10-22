package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.common.routing.RouteFlags;
import com.acme.insurance.pas.common.routing.RouteState;
import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.PremiumOverflowException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PremiumJobConfiguration {
  @Bean
  PlatformTransactionManager premiumTransactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean
  Partitioner premiumPartitioner(NamedParameterJdbcTemplate jdbcTemplate) {
    return new PremiumPartitioner(jdbcTemplate);
  }

  @Bean
  TaskExecutor premiumPartitionTaskExecutor(
      @Value("${pas.batch.premium.concurrency:${pas.batch.premium.grid-size:8}}") int concurrency) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setThreadNamePrefix("premium-batch-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    return executor;
  }

  @Bean
  Step premiumRouteGuardStep(
      JobRepository repository,
      @Qualifier("premiumTransactionManager") PlatformTransactionManager premiumTransactionManager,
      RouteFlags routes,
      JobExplorer jobExplorer,
      JdbcTemplate jdbcTemplate) {
    return new StepBuilder("premiumRouteGuardStep", repository)
        .tasklet(
            (contribution, chunkContext) -> {
              JobExecution current = contribution.getStepExecution().getJobExecution();
              boolean anotherRunning =
                  jobExplorer.findRunningJobExecutions("premiumRecalculationJob").stream()
                      .anyMatch(execution -> !execution.getId().equals(current.getId()));
              if (anotherRunning) {
                throw new IllegalStateException(
                    "A premium recalculation execution is already running");
              }
              RouteState state = routes.state(RouteFlags.PREMIUM_BATCH);
              if (state == RouteState.LEGACY) {
                contribution.setExitStatus(
                    new org.springframework.batch.core.ExitStatus("DISABLED"));
              } else {
                if (state == RouteState.SHADOW) {
                  LocalDate businessDate =
                      LocalDate.parse(current.getJobParameters().getString("businessDate"));
                  jdbcTemplate.update(
                      "DELETE FROM pas_shadow.premiums WHERE business_date = ?", businessDate);
                }
                current
                    .getExecutionContext()
                    .putString("premiumWriteTarget", state == RouteState.SHADOW ? "SHADOW" : "PAS");
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            premiumTransactionManager)
        .build();
  }

  @Bean
  @StepScope
  JdbcPagingItemReader<PremiumPolicy> premiumPolicyReader(
      DataSource dataSource,
      @Value("#{stepExecutionContext['minimumPolicy']}") String minimumPolicy,
      @Value("#{stepExecutionContext['maximumPolicy']}") String maximumPolicy,
      @Value("#{stepExecutionContext['empty']}") boolean empty,
      @Value("${pas.batch.premium.chunk-size:500}") int chunkSize) {
    PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
    queryProvider.setSelectClause(
        "SELECT policy_number, policy_type, total_premium, deductible, coverage_limit, "
            + "effective_date, expiry_date");
    queryProvider.setFromClause("FROM pas.policies");
    queryProvider.setWhereClause(
        empty
            ? "WHERE 1 = 0"
            : "WHERE policy_status = 'AC' AND policy_number BETWEEN :minimumPolicy AND :maximumPolicy");
    queryProvider.setSortKeys(
        Map.of("policy_number", org.springframework.batch.item.database.Order.ASCENDING));
    return new JdbcPagingItemReaderBuilder<PremiumPolicy>()
        .name("premiumPolicyReader")
        .dataSource(dataSource)
        .queryProvider(queryProvider)
        .parameterValues(
            empty
                ? Map.of()
                : Map.of("minimumPolicy", minimumPolicy, "maximumPolicy", maximumPolicy))
        .pageSize(chunkSize)
        .saveState(true)
        .rowMapper(
            (resultSet, rowNumber) ->
                new PremiumPolicy(
                    resultSet.getString("policy_number"),
                    resultSet.getString("policy_type"),
                    resultSet.getBigDecimal("total_premium"),
                    resultSet.getBigDecimal("deductible"),
                    resultSet.getBigDecimal("coverage_limit"),
                    resultSet.getDate("effective_date").toLocalDate(),
                    resultSet.getDate("expiry_date").toLocalDate()))
        .build();
  }

  @Bean
  @StepScope
  ItemProcessor<PremiumPolicy, PremiumRow> premiumProcessor(
      PremiumCalculator calculator,
      @Value("#{jobParameters['businessDate']}") String businessDate) {
    return new PremiumProcessor(calculator, LocalDate.parse(businessDate));
  }

  @Bean
  @StepScope
  ItemWriter<PremiumRow> premiumItemWriter(
      JdbcTemplate jdbcTemplate,
      @Value("#{jobExecutionContext['premiumWriteTarget']}") String target,
      @Value("#{stepExecution.jobExecution.jobInstance.id}") long jobInstanceId) {
    return new PremiumItemWriter(jdbcTemplate, target, jobInstanceId);
  }

  @Bean
  @StepScope
  PremiumSkipListener premiumSkipListener(
      JdbcTemplate jdbcTemplate,
      @Value("#{stepExecution.jobExecution.jobInstance.id}") long jobInstanceId) {
    return new PremiumSkipListener(jdbcTemplate, jobInstanceId);
  }

  @Bean
  Step premiumWorkerStep(
      JobRepository repository,
      @Qualifier("premiumTransactionManager") PlatformTransactionManager premiumTransactionManager,
      JdbcPagingItemReader<PremiumPolicy> premiumPolicyReader,
      ItemProcessor<PremiumPolicy, PremiumRow> premiumProcessor,
      ItemWriter<PremiumRow> premiumItemWriter,
      PremiumSkipListener premiumSkipListener,
      @Value("${pas.batch.premium.chunk-size:500}") int chunkSize,
      @Value("${pas.batch.premium.skip-limit:1000}") int skipLimit) {
    return new StepBuilder("premiumWorkerStep", repository)
        .<PremiumPolicy, PremiumRow>chunk(chunkSize, premiumTransactionManager)
        .reader(premiumPolicyReader)
        .processor(premiumProcessor)
        .writer(premiumItemWriter)
        .faultTolerant()
        .skip(PremiumOverflowException.class)
        .skip(DataIntegrityViolationException.class)
        .skip(PolicyRatingException.class)
        .skipLimit(skipLimit)
        .listener(premiumSkipListener)
        .build();
  }

  @Bean
  Step premiumPartitionStep(
      JobRepository repository,
      Step premiumWorkerStep,
      Partitioner premiumPartitioner,
      TaskExecutor premiumPartitionTaskExecutor,
      @Value("${pas.batch.premium.grid-size:8}") int gridSize) {
    return new StepBuilder("premiumPartitionStep", repository)
        .partitioner("premiumWorkerStep", premiumPartitioner)
        .step(premiumWorkerStep)
        .gridSize(gridSize)
        .taskExecutor(premiumPartitionTaskExecutor)
        .build();
  }

  @Bean
  @StepScope
  Tasklet premiumReportTasklet(
      JdbcTemplate jdbcTemplate,
      @Value("#{jobParameters['businessDate']}") String businessDate,
      @Value("#{stepExecution.jobExecution.id}") long jobExecutionId,
      @Value("#{stepExecution.jobExecution.jobInstance.id}") long jobInstanceId,
      @Value("${pas.batch.premium.report-dir:/var/lib/pas-batch/reports}") String reportDirectory) {
    return new PremiumReportTasklet(
        jdbcTemplate,
        LocalDate.parse(businessDate),
        jobExecutionId,
        jobInstanceId,
        reportDirectory);
  }

  @Bean
  Step premiumReportStep(
      JobRepository repository,
      @Qualifier("premiumTransactionManager") PlatformTransactionManager premiumTransactionManager,
      Tasklet premiumReportTasklet) {
    return new StepBuilder("premiumReportStep", repository)
        .tasklet(premiumReportTasklet, premiumTransactionManager)
        .build();
  }

  @Bean
  JobExecutionListener premiumRunListener(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
    return new PremiumRunListener(jdbcTemplate, registry);
  }

  @Bean
  Job premiumRecalculationJob(
      JobRepository repository,
      Step premiumRouteGuardStep,
      Step premiumPartitionStep,
      Step premiumReportStep,
      JobExecutionListener premiumRunListener,
      com.acme.insurance.pas.batch.platform.JobFailureListener jobFailureListener) {
    return new JobBuilder("premiumRecalculationJob", repository)
        .listener(premiumRunListener)
        .listener(jobFailureListener)
        .start(premiumRouteGuardStep)
        .on("DISABLED")
        .end("DISABLED")
        .from(premiumRouteGuardStep)
        .on("*")
        .to(premiumPartitionStep)
        .next(premiumReportStep)
        .end()
        .build();
  }
}
