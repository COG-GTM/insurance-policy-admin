package com.acme.insurance.pas.batch.migration;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code db2BulkLoadJob}: initial load of ACMEINS into {@code pas.*}. One chunk-oriented step per
 * table in foreign-key order; each chunk is read with keyset paging on the DB2 primary key and
 * upserted in one target transaction, so the job is restartable (Spring Batch saves the reader
 * position) and re-runnable (upserts converge). Watermarks captured before the first read seed the
 * delta sync, so changes made during the load are picked up by the next delta.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
public class BulkLoadJobConfig {

  public static final String JOB_NAME = "db2BulkLoadJob";
  static final String WATERMARK_PREFIX = "watermark.";

  @Bean
  Job db2BulkLoadJob(
      JobRepository repository,
      PlatformTransactionManager tx,
      SourceDatabase source,
      TargetWriter writer,
      SyncState syncState,
      MigrationProperties properties)
      throws Exception {
    JobBuilder builder = new JobBuilder(JOB_NAME, repository);
    var flow =
        builder
            .incrementer(new RunIdIncrementer())
            .start(captureWatermarks(repository, tx, source));
    for (MigrationTable table : MigrationTable.values()) {
      flow = flow.next(loadStep(repository, tx, source, writer, properties, table));
    }
    return flow.next(saveWatermarks(repository, tx, syncState)).build();
  }

  private static Step captureWatermarks(
      JobRepository repository, PlatformTransactionManager tx, SourceDatabase source) {
    return new StepBuilder("db2BulkLoad.captureWatermarks", repository)
        .tasklet(
            (contribution, chunk) -> {
              ExecutionContext jobContext =
                  chunk.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
              for (MigrationTable table : MigrationTable.values()) {
                if (table.watermarkColumn() == null) {
                  continue;
                }
                Timestamp max =
                    source
                        .jdbc()
                        .queryForObject(
                            "SELECT MAX("
                                + table.watermarkColumn().toUpperCase(Locale.ROOT)
                                + ") FROM "
                                + source.table(table.sourceTable()),
                            Timestamp.class);
                // An empty table still gets a lower bound so the delta picks up its first row.
                jobContext.put(
                    WATERMARK_PREFIX + table.name(),
                    (max == null ? DeltaSyncService.NO_WATERMARK : max.toLocalDateTime())
                        .toString());
              }
              return RepeatStatus.FINISHED;
            },
            tx)
        .build();
  }

  private static Step saveWatermarks(
      JobRepository repository, PlatformTransactionManager tx, SyncState syncState) {
    return new StepBuilder("db2BulkLoad.saveWatermarks", repository)
        .tasklet(
            (contribution, chunk) -> {
              var jobExecution = chunk.getStepContext().getStepExecution().getJobExecution();
              ExecutionContext jobContext = jobExecution.getExecutionContext();
              String runId = JOB_NAME + "-" + jobExecution.getId();
              for (MigrationTable table : MigrationTable.values()) {
                String value = jobContext.getString(WATERMARK_PREFIX + table.name(), null);
                if (value != null) {
                  syncState.saveWatermark(table, LocalDateTime.parse(value), 0, runId);
                }
              }
              return RepeatStatus.FINISHED;
            },
            tx)
        .build();
  }

  private static Step loadStep(
      JobRepository repository,
      PlatformTransactionManager tx,
      SourceDatabase source,
      TargetWriter writer,
      MigrationProperties properties,
      MigrationTable table)
      throws Exception {
    return new StepBuilder("db2BulkLoad." + table.name(), repository)
        .<Map<String, Object>, Map<String, Object>>chunk(properties.chunkSize(), tx)
        .reader(reader(source, properties, table))
        .writer(chunk -> writer.write(table, chunk.getItems()))
        .build();
  }

  static JdbcPagingItemReader<Map<String, Object>> reader(
      SourceDatabase source, MigrationProperties properties, MigrationTable table)
      throws Exception {
    SqlPagingQueryProviderFactoryBean provider = new SqlPagingQueryProviderFactoryBean();
    provider.setDataSource(source.dataSource());
    provider.setSelectClause(
        "SELECT " + String.join(", ", table.sourceColumns()).toUpperCase(Locale.ROOT));
    provider.setFromClause("FROM " + source.table(table.sourceTable()));
    Map<String, Order> sortKeys = new LinkedHashMap<>();
    for (String key : table.keyColumns()) {
      sortKeys.put(key.toUpperCase(Locale.ROOT), Order.ASCENDING);
    }
    provider.setSortKeys(sortKeys);

    JdbcPagingItemReader<Map<String, Object>> reader = new JdbcPagingItemReader<>();
    reader.setName("db2BulkLoad." + table.name() + ".reader");
    reader.setDataSource(source.dataSource());
    reader.setQueryProvider(java.util.Objects.requireNonNull(provider.getObject()));
    reader.setPageSize(properties.chunkSize());
    reader.setRowMapper(SourceRows.INSTANCE);
    reader.afterPropertiesSet();
    return reader;
  }
}
