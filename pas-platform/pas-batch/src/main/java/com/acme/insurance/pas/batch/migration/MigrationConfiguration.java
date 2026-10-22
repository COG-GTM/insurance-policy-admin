package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.ReverseSyncTarget;
import com.acme.insurance.pas.batch.platform.JobFailureListener;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
public class MigrationConfiguration {
  @Bean(destroyMethod = "close")
  Db2Source db2Source(MigrationProperties properties) {
    return new Db2Source(properties);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "pas.migration.reverse-sync.enabled", havingValue = "true")
  ReverseSyncTarget reverseSyncTarget(MigrationProperties properties) {
    MigrationProperties.Source source = properties.source();
    MigrationProperties.ReverseSync reverse = properties.reverseSync();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(Db2Source.jdbcUrl(source));
    config.setUsername(reverse.username());
    config.setPassword(reverse.password());
    config.setMaximumPoolSize(2);
    config.setReadOnly(false);
    config.setPoolName("pas-db2-reverse-sync");
    return new ReverseSyncTarget(new HikariDataSource(config));
  }

  @Bean
  JobFailureListener migrationJobFailureListener(MeterRegistry registry) {
    return new JobFailureListener(registry);
  }
}
