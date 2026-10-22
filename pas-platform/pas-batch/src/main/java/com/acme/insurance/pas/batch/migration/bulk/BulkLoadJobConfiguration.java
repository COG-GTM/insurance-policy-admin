package com.acme.insurance.pas.batch.migration.bulk;

import com.acme.insurance.pas.batch.migration.MigrationProperties;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.batch.platform.JobFailureListener;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
public class BulkLoadJobConfiguration {
  @Bean("migrationTransactionManager")
  PlatformTransactionManager migrationTransactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean
  @StepScope
  ItemStreamReader<MigrationRow> bulkMigrationReader(
      Db2Source source,
      @Value("#{stepExecutionContext['table']}") String tableName,
      @Value("#{stepExecutionContext['minimum']}") String minimum,
      @Value("#{stepExecutionContext['maximum']}") String maximum,
      @Value("${pas.migration.bulk.chunk-size:500}") int chunkSize) {
    return new MigrationRowReader(
        source, MigrationTable.valueOf(tableName), minimum, maximum, chunkSize);
  }

  @Bean
  Step bulkLoadWorkerStep(
      JobRepository repository,
      @Qualifier("migrationTransactionManager") PlatformTransactionManager transactionManager,
      ItemStreamReader<MigrationRow> bulkMigrationReader,
      MigrationRowWriter writer,
      MigrationProperties properties) {
    return new StepBuilder("bulkLoadWorkerStep", repository)
        .<MigrationRow, MigrationRow>chunk(properties.bulk().chunkSize(), transactionManager)
        .reader(bulkMigrationReader)
        .writer(writer)
        .build();
  }

  @Bean
  Job bulkLoadJob(
      JobRepository repository,
      Step bulkLoadWorkerStep,
      Db2Source source,
      MigrationProperties properties,
      JobFailureListener migrationJobFailureListener) {
    var tables = MigrationTable.bulkLoadOrder();
    var jobBuilder =
        new JobBuilder("bulkLoadJob", repository)
            .start(
                partitionStep(
                    repository, bulkLoadWorkerStep, source, properties, tables.getFirst()));
    for (MigrationTable table : tables.subList(1, tables.size())) {
      jobBuilder.next(partitionStep(repository, bulkLoadWorkerStep, source, properties, table));
    }
    return jobBuilder.listener(migrationJobFailureListener).build();
  }

  private Step partitionStep(
      JobRepository repository,
      Step bulkLoadWorkerStep,
      Db2Source source,
      MigrationProperties properties,
      MigrationTable table) {
    SimpleAsyncTaskExecutor executor =
        new SimpleAsyncTaskExecutor("pas-bulk-" + table.name().toLowerCase(Locale.ROOT) + "-");
    executor.setConcurrencyLimit(properties.bulk().gridSize());
    Partitioner partitioner = new BulkLoadPartitioner(source, properties.bulk().gridSize(), table);
    return new StepBuilder("bulkLoadPartitionStep-" + table.name(), repository)
        .partitioner(bulkLoadWorkerStep.getName(), partitioner)
        .step(bulkLoadWorkerStep)
        .gridSize(properties.bulk().gridSize())
        .taskExecutor(executor)
        .build();
  }
}
