package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.batch.extract.delivery.ExtractDeliveryService;
import com.acme.insurance.pas.batch.extract.delivery.ExtractDeliveryTasklet;
import com.acme.insurance.pas.batch.extract.delivery.SftpProperties;
import com.acme.insurance.pas.batch.extract.layout.ExtractLayout;
import com.acme.insurance.pas.batch.extract.layout.ExtractLayouts;
import com.acme.insurance.pas.batch.extract.layout.ExtractRecordEncoder;
import com.acme.insurance.pas.batch.extract.routing.ExtractRouteProperties;
import com.acme.insurance.pas.batch.extract.routing.RouteMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableConfigurationProperties({
  ExtractRouteProperties.class,
  SftpProperties.class,
  com.acme.insurance.pas.batch.extract.delivery.ExtractRetryProperties.class
})
public class ExtractJobConfiguration {
  @Bean
  JobExecutionListener extractFailureListener(
      io.micrometer.core.instrument.MeterRegistry registry) {
    return new ExtractFailureListener(registry);
  }

  @Bean
  PlatformTransactionManager extractTransactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean
  ExtractDeliveryTasklet extractDeliveryTasklet(
      ExtractDeliveryService deliveryService,
      ExtractRouteProperties routes,
      @Value("${pas.batch.extract.work-dir:/var/lib/pas-batch/extracts}") String workDir) {
    return new ExtractDeliveryTasklet(deliveryService, routes, workDir);
  }

  @Bean
  Step extractRouteGuardStep(
      JobRepository jobRepository,
      PlatformTransactionManager extractTransactionManager,
      ExtractRouteProperties routes) {
    return new StepBuilder("extractRouteGuardStep", jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              String jobName =
                  contribution.getStepExecution().getJobExecution().getJobInstance().getJobName();
              if (routes.forJob(jobName) == RouteMode.RETIRED) {
                throw new IllegalStateException("Extract capability is retired");
              }
              return org.springframework.batch.repeat.RepeatStatus.FINISHED;
            },
            extractTransactionManager)
        .build();
  }

  @Bean
  @StepScope
  JdbcCursorItemReader<Map<String, Object>> policyExtractReader(
      DataSource dataSource,
      @Value("#{jobParameters['businessDate']}") String businessDate,
      @Value("${pas.batch.extract.zone:America/New_York}") String zone) {
    LocalDate date = LocalDate.parse(businessDate);
    OffsetDateTime windowStart =
        date.minusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().atOffset(ZoneOffset.UTC);
    PreparedStatementSetter setter = statement -> statement.setObject(1, windowStart);
    return new JdbcCursorItemReaderBuilder<Map<String, Object>>()
        .name("policyExtractReader")
        .dataSource(dataSource)
        .sql(ExtractSql.POLICY)
        .preparedStatementSetter(setter)
        .rowMapper(rowMapper())
        .build();
  }

  @Bean
  @StepScope
  JdbcCursorItemReader<Map<String, Object>> coverageExtractReader(DataSource dataSource) {
    return reader("coverageExtractReader", ExtractSql.COVERAGE, dataSource);
  }

  @Bean
  @StepScope
  JdbcCursorItemReader<Map<String, Object>> exposureExtractReader(DataSource dataSource) {
    return reader("exposureExtractReader", ExtractSql.EXPOSURE, dataSource);
  }

  @Bean
  @StepScope
  FlatFileItemWriter<Map<String, Object>> policyExtractWriter(
      @Value("${pas.batch.extract.work-dir:/var/lib/pas-batch/extracts}") String workDir,
      @Value("#{jobParameters['businessDate']}") String businessDate,
      @Value("#{stepExecution.jobExecution.id}") Long executionId) {
    return fixedWriter(
        "policyExtractWriter",
        extractPath(workDir, "dailyExtractJob", businessDate, executionId, "policy_extract.dat"),
        ExtractLayouts.DAILY_POLICY);
  }

  @Bean
  @StepScope
  FlatFileItemWriter<Map<String, Object>> coverageExtractWriter(
      @Value("${pas.batch.extract.work-dir:/var/lib/pas-batch/extracts}") String workDir,
      @Value("#{jobParameters['businessDate']}") String businessDate,
      @Value("#{stepExecution.jobExecution.id}") Long executionId) {
    return fixedWriter(
        "coverageExtractWriter",
        extractPath(workDir, "dailyExtractJob", businessDate, executionId, "coverage_extract.dat"),
        ExtractLayouts.DAILY_COVERAGE);
  }

  @Bean
  @StepScope
  FlatFileItemWriter<Map<String, Object>> exposureExtractWriter(
      @Value("${pas.batch.extract.work-dir:/var/lib/pas-batch/extracts}") String workDir,
      @Value("#{jobParameters['businessDate']}") String businessDate,
      @Value("#{stepExecution.jobExecution.id}") Long executionId) {
    String suffix =
        LocalDate.parse(businessDate)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
    return new FlatFileItemWriterBuilder<Map<String, Object>>()
        .name("exposureExtractWriter")
        .resource(
            new FileSystemResource(
                extractPath(
                    workDir,
                    "monthlyExposureJob",
                    businessDate,
                    executionId,
                    "exposure_monthly.D" + suffix + ".csv")))
        .encoding(StandardCharsets.ISO_8859_1.name())
        .lineSeparator(ExtractLayouts.MONTHLY_EXPOSURE.recordSeparator())
        .lineAggregator(ExtractRecordEncoder::encodeCsv)
        .build();
  }

  @Bean
  Step policyExtractStep(
      JobRepository jobRepository,
      PlatformTransactionManager extractTransactionManager,
      JdbcCursorItemReader<Map<String, Object>> policyExtractReader,
      FlatFileItemWriter<Map<String, Object>> policyExtractWriter) {
    return chunkStep(
        "policyExtractStep",
        jobRepository,
        extractTransactionManager,
        policyExtractReader,
        policyExtractWriter);
  }

  @Bean
  Step coverageExtractStep(
      JobRepository jobRepository,
      PlatformTransactionManager extractTransactionManager,
      JdbcCursorItemReader<Map<String, Object>> coverageExtractReader,
      FlatFileItemWriter<Map<String, Object>> coverageExtractWriter) {
    return chunkStep(
        "coverageExtractStep",
        jobRepository,
        extractTransactionManager,
        coverageExtractReader,
        coverageExtractWriter);
  }

  @Bean
  Step exposureExtractStep(
      JobRepository jobRepository,
      PlatformTransactionManager extractTransactionManager,
      JdbcCursorItemReader<Map<String, Object>> exposureExtractReader,
      FlatFileItemWriter<Map<String, Object>> exposureExtractWriter) {
    return chunkStep(
        "exposureExtractStep",
        jobRepository,
        extractTransactionManager,
        exposureExtractReader,
        exposureExtractWriter);
  }

  @Bean
  Step claimsDeliveryStep(
      JobRepository repository,
      PlatformTransactionManager transactionManager,
      ExtractRouteProperties routes,
      ExtractDeliveryTasklet deliveryTasklet) {
    return new StepBuilder("claimsDeliveryStep", repository)
        .tasklet(deliveryTasklet.forTarget("claims", routes.dailyExtract()), transactionManager)
        .build();
  }

  @Bean
  Step actuarialDeliveryStep(
      JobRepository repository,
      PlatformTransactionManager transactionManager,
      ExtractRouteProperties routes,
      ExtractDeliveryTasklet deliveryTasklet) {
    return new StepBuilder("actuarialDeliveryStep", repository)
        .tasklet(
            deliveryTasklet.forTarget("actuarial", routes.monthlyExposure()), transactionManager)
        .build();
  }

  @Bean
  Job dailyExtractJob(
      JobRepository repository,
      Step extractRouteGuardStep,
      Step policyExtractStep,
      Step coverageExtractStep,
      Step claimsDeliveryStep,
      JobExecutionListener extractFailureListener) {
    return new JobBuilder("dailyExtractJob", repository)
        .listener(extractFailureListener)
        .start(extractRouteGuardStep)
        .next(policyExtractStep)
        .next(coverageExtractStep)
        .next(claimsDeliveryStep)
        .build();
  }

  @Bean
  Job monthlyExposureJob(
      JobRepository repository,
      Step extractRouteGuardStep,
      Step exposureExtractStep,
      Step actuarialDeliveryStep,
      JobExecutionListener extractFailureListener) {
    return new JobBuilder("monthlyExposureJob", repository)
        .listener(extractFailureListener)
        .start(extractRouteGuardStep)
        .next(exposureExtractStep)
        .next(actuarialDeliveryStep)
        .build();
  }

  private static FlatFileItemWriter<Map<String, Object>> fixedWriter(
      String name, String path, ExtractLayout layout) {
    return new FlatFileItemWriterBuilder<Map<String, Object>>()
        .name(name)
        .resource(new FileSystemResource(path))
        .encoding(layout.charset().name())
        .lineSeparator(layout.recordSeparator())
        .lineAggregator(
            row ->
                new String(
                    ExtractRecordEncoder.encodeFixed(layout, row), StandardCharsets.ISO_8859_1))
        .build();
  }

  private static Step chunkStep(
      String name,
      JobRepository repository,
      PlatformTransactionManager transactionManager,
      JdbcCursorItemReader<Map<String, Object>> reader,
      FlatFileItemWriter<Map<String, Object>> writer) {
    return new StepBuilder(name, repository)
        .<Map<String, Object>, Map<String, Object>>chunk(500, transactionManager)
        .allowStartIfComplete(true)
        .reader(reader)
        .writer(writer)
        .build();
  }

  private static JdbcCursorItemReader<Map<String, Object>> reader(
      String name, String sql, DataSource dataSource) {
    return new JdbcCursorItemReaderBuilder<Map<String, Object>>()
        .name(name)
        .dataSource(dataSource)
        .sql(sql)
        .rowMapper(rowMapper())
        .build();
  }

  private static RowMapper<Map<String, Object>> rowMapper() {
    return (ResultSet resultSet, int rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      var metadata = resultSet.getMetaData();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        String name = metadata.getColumnLabel(index).toUpperCase(java.util.Locale.ROOT);
        Object value = resultSet.getObject(index);
        if (value instanceof java.sql.Timestamp timestamp) {
          value = timestamp.toLocalDateTime();
        } else if (value instanceof java.sql.Date date) {
          value = date.toLocalDate();
        } else if (value instanceof String text) {
          value = rtrim(text);
        }
        row.put(name, value);
      }
      return row;
    };
  }

  private static String rtrim(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') {
      end--;
    }
    return value.substring(0, end);
  }

  private static String extractPath(
      String workDir, String jobName, String businessDate, Long executionId, String fileName) {
    return java.nio.file.Path.of(workDir, jobName, businessDate, executionId.toString(), fileName)
        .toString();
  }

  private static final class ExtractFailureListener implements JobExecutionListener {
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ExtractFailureListener.class);
    private final io.micrometer.core.instrument.MeterRegistry registry;

    private ExtractFailureListener(io.micrometer.core.instrument.MeterRegistry registry) {
      this.registry = registry;
    }

    @Override
    public void afterJob(JobExecution execution) {
      if (execution.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED) {
        return;
      }
      registry
          .counter("pas.batch.job.failures", "job", execution.getJobInstance().getJobName())
          .increment();
      String failureTypes =
          execution.getAllFailureExceptions().stream()
              .map(exception -> exception.getClass().getSimpleName())
              .distinct()
              .sorted()
              .collect(java.util.stream.Collectors.joining(","));
      log.atError()
          .addKeyValue("event", "pas.batch.job.failed")
          .addKeyValue("job", execution.getJobInstance().getJobName())
          .addKeyValue("jobExecutionId", execution.getId())
          .addKeyValue("businessDate", execution.getJobParameters().getString("businessDate"))
          .addKeyValue("exitCode", execution.getExitStatus().getExitCode())
          .addKeyValue("failureType", failureTypes)
          .log("Extract job failed");
    }
  }
}
