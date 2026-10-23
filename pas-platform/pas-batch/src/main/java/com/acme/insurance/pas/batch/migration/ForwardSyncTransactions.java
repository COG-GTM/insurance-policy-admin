package com.acme.insurance.pas.batch.migration;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "pas.migration.enabled", havingValue = "true")
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are retained for the lifetime of this helper.")
public final class ForwardSyncTransactions {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  public ForwardSyncTransactions(
      @Qualifier("migrationTransactionManager") PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public ForwardSyncTransactions(JdbcTemplate jdbc) {
    this(
        new DataSourceTransactionManager(
            Objects.requireNonNull(jdbc.getDataSource(), "target DataSource is required")),
        jdbc);
  }

  public void run(Runnable action) {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbc.execute("SET LOCAL pas.change_origin = 'forward-sync'");
          action.run();
        });
  }
}
