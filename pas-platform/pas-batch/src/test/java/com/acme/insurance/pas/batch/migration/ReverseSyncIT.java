package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.reverse.ReverseSyncService;
import com.acme.insurance.pas.batch.migration.source.ReverseSyncTarget;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class ReverseSyncIT extends MigrationIntegrationTestSupport {
  @Test
  void disabledByDefaultAndEnabledDrainIsIdempotentAndDecryptsPii() throws Exception {
    seedSource(2);
    bulkLoadAll();
    assertThat(target.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();
    java.math.BigDecimal untouchedPremium =
        sourceJdbc.queryForObject(
            "SELECT total_premium FROM acmeins.policies WHERE policy_number = 'POL000000002'",
            java.math.BigDecimal.class);
    LocalDateTime untouchedLastUpdated =
        sourceJdbc.queryForObject(
            "SELECT last_updated FROM acmeins.policies WHERE policy_number = 'POL000000002'",
            LocalDateTime.class);
    StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
    ObjectProvider<ReverseSyncTarget> emptyProvider =
        emptyFactory.getBeanProvider(ReverseSyncTarget.class);
    ReverseSyncService disabled =
        new ReverseSyncService(
            target, source, emptyProvider, properties, cipher, metrics, targetTransactionManager());
    assertThat(disabled.drain()).isZero();
    assertThat(disabled.pruneChangeLog()).isZero();

    target.update(
        "UPDATE pas.policies SET total_premium = 8000.00, row_version = row_version + 1, "
            + "last_updated = TIMESTAMPTZ '2026-06-01 12:00:00+00' "
            + "WHERE policy_number = 'POL000000001'");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(SOURCE.getJdbcUrl());
    config.setUsername(SOURCE.getUsername());
    config.setPassword(SOURCE.getPassword());
    config.setMaximumPoolSize(1);
    config.setPoolName("reverse-sync-it");
    ReverseSyncTarget reverseTarget = new ReverseSyncTarget(new HikariDataSource(config));
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("reverseSyncTarget", reverseTarget);
    ObjectProvider<ReverseSyncTarget> targetProvider =
        beanFactory.getBeanProvider(ReverseSyncTarget.class);
    ReverseSyncService enabled =
        new ReverseSyncService(
            target,
            source,
            targetProvider,
            withReverseSyncEnabled(),
            cipher,
            metrics,
            targetTransactionManager());

    assertThat(enabled.drain()).isEqualTo(1);
    assertThat(enabled.drain()).isZero();
    assertThat(
            sourceJdbc.queryForObject(
                "SELECT total_premium FROM acmeins.policies "
                    + "WHERE policy_number = 'POL000000001'",
                java.math.BigDecimal.class))
        .isEqualByComparingTo("8000.00");
    assertThat(
            sourceJdbc.queryForObject(
                "SELECT last_updated FROM acmeins.policies "
                    + "WHERE policy_number = 'POL000000001'",
                LocalDateTime.class))
        .isEqualTo(
            Instant.parse("2026-06-01T12:00:00Z")
                .atZone(ZoneId.of(properties.source().zone()))
                .toLocalDateTime());
    assertThat(
            sourceJdbc.queryForObject(
                "SELECT total_premium FROM acmeins.policies "
                    + "WHERE policy_number = 'POL000000002'",
                java.math.BigDecimal.class))
        .isEqualByComparingTo(untouchedPremium);
    assertThat(
            sourceJdbc.queryForObject(
                "SELECT last_updated FROM acmeins.policies "
                    + "WHERE policy_number = 'POL000000002'",
                LocalDateTime.class))
        .isEqualTo(untouchedLastUpdated);
    assertThat(
            sourceJdbc.queryForObject(
                "SELECT tax_id FROM acmeins.policy_holders WHERE cust_id = 'C000000001'",
                String.class))
        .isEqualTo("TAX0000001");
    reverseTarget.close();
  }

  @Test
  void premiumOnlyChangeIsDrainedWithoutPolicyVersionChanges() throws Exception {
    seedSource(1);
    bulkLoadAll();
    target.update(
        "UPDATE pas.premiums SET total_premium = 7777.00 "
            + "WHERE policy_number = 'POL000000001'");
    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      ReverseSyncService service = enabledService(reverseTarget);
      assertThat(service.drain()).isGreaterThan(0);
      assertThat(
              sourceJdbc.queryForObject(
                  "SELECT total_premium FROM acmeins.premiums "
                      + "WHERE policy_number = 'POL000000001' ORDER BY calc_date DESC FETCH FIRST 1 ROW ONLY",
                  java.math.BigDecimal.class))
          .isEqualByComparingTo("7777.00");
    } finally {
      reverseTarget.close();
    }
  }

  @Test
  void sameHolderTimestampAcrossConsecutiveDrainsReplicatesBothRows() throws Exception {
    seedSource(2);
    bulkLoadAll();
    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      ReverseSyncService service = enabledService(reverseTarget);
      target.update(
          "UPDATE pas.policy_holders SET risk_tier = 'U', "
              + "last_updated = TIMESTAMPTZ '2026-06-01 12:00:00+00' "
              + "WHERE cust_id = 'C000000001'");
      assertThat(service.drain()).isGreaterThan(0);
      target.update(
          "UPDATE pas.policy_holders SET risk_tier = 'P', "
              + "last_updated = TIMESTAMPTZ '2026-06-01 12:00:00+00' "
              + "WHERE cust_id = 'C000000002'");
      assertThat(service.drain()).isGreaterThan(0);

      assertThat(
              sourceJdbc.queryForObject(
                  "SELECT risk_tier FROM acmeins.policy_holders WHERE cust_id = 'C000000001'",
                  String.class))
          .isEqualTo("U");
      assertThat(
              sourceJdbc.queryForObject(
                  "SELECT risk_tier FROM acmeins.policy_holders WHERE cust_id = 'C000000002'",
                  String.class))
          .isEqualTo("P");
      assertThat(
              sourceJdbc.queryForObject(
                  "SELECT count(*) FROM acmeins.policy_holders "
                      + "WHERE cust_id IN ('C000000001', 'C000000002') AND last_updated = ?",
                  Integer.class,
                  LocalDateTime.of(2026, 6, 1, 8, 0)))
          .isEqualTo(2);
    } finally {
      reverseTarget.close();
    }
  }

  @Test
  void inFlightChangeIsReplayedAfterItsTransactionCommits() throws Exception {
    seedSource(2);
    bulkLoadAll();
    java.math.BigDecimal originalA =
        sourceJdbc.queryForObject(
            "SELECT total_premium FROM acmeins.policies WHERE policy_number = 'POL000000001'",
            java.math.BigDecimal.class);
    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      ReverseSyncService service = enabledService(reverseTarget);
      try (Connection transactionA =
              DriverManager.getConnection(
                  TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
          var statement = transactionA.createStatement()) {
        transactionA.setAutoCommit(false);
        statement.execute(
            "UPDATE pas.policies SET total_premium = 7000.00, "
                + "row_version = row_version + 1, "
                + "last_updated = TIMESTAMPTZ '2026-06-02 12:00:00+00' "
                + "WHERE policy_number = 'POL000000001'");
        target.update(
            "UPDATE pas.policies SET total_premium = 8000.00, "
                + "row_version = row_version + 1, "
                + "last_updated = TIMESTAMPTZ '2026-06-02 12:00:01+00' "
                + "WHERE policy_number = 'POL000000002'");

        assertThat(service.syncOnce()).isEqualTo(1);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000001'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo(originalA);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000002'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo("8000.00");

        transactionA.commit();
        assertThat(service.syncOnce()).isGreaterThan(0);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000001'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo("7000.00");
      }
    } finally {
      reverseTarget.close();
    }
  }

  @Test
  void drainDoesNotReplayCommittedRowsWhileAnOlderTransactionRemainsOpen() throws Exception {
    seedSource(2);
    bulkLoadAll();
    java.math.BigDecimal originalA =
        sourceJdbc.queryForObject(
            "SELECT total_premium FROM acmeins.policies WHERE policy_number = 'POL000000001'",
            java.math.BigDecimal.class);
    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      ReverseSyncService service = enabledService(reverseTarget);
      try (Connection transactionA =
              DriverManager.getConnection(
                  TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword());
          var statement = transactionA.createStatement()) {
        transactionA.setAutoCommit(false);
        statement.execute(
            "UPDATE pas.policies SET total_premium = 7000.00, "
                + "row_version = row_version + 1, "
                + "last_updated = TIMESTAMPTZ '2026-06-02 12:00:00+00' "
                + "WHERE policy_number = 'POL000000001'");
        target.update(
            "UPDATE pas.policies SET total_premium = 8000.00, "
                + "row_version = row_version + 1, "
                + "last_updated = TIMESTAMPTZ '2026-06-02 12:00:01+00' "
                + "WHERE policy_number = 'POL000000002'");

        assertThat(service.drain()).isEqualTo(1);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000001'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo(originalA);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000002'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo("8000.00");

        transactionA.commit();
        assertThat(service.drain()).isEqualTo(1);
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000001'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo("7000.00");
        assertThat(
                sourceJdbc.queryForObject(
                    "SELECT total_premium FROM acmeins.policies "
                        + "WHERE policy_number = 'POL000000002'",
                    java.math.BigDecimal.class))
            .isEqualByComparingTo("8000.00");
      }
    } finally {
      reverseTarget.close();
    }
  }

  @Test
  void pruningDeletesOnlyRowsOlderThanTheReverseXmin() throws Exception {
    seedSource(1);
    bulkLoadAll();
    target.update(
        "INSERT INTO pas.change_log (table_name, record_key) VALUES ('POLICIES', 'OLDER')");
    String reverseXmin = target.queryForObject("SELECT pg_current_xact_id()::text", String.class);
    target.update(
        """
        INSERT INTO pas_batch.sync_watermark
            (table_name, direction, watermark_value, reverse_xmin, synced_through, updated_at)
        VALUES ('CHANGE_LOG', 'REVERSE', now(), ?::xid8, now(), now())
        ON CONFLICT (table_name, direction) DO UPDATE SET reverse_xmin = EXCLUDED.reverse_xmin
        """,
        reverseXmin);
    target.update(
        "INSERT INTO pas.change_log (table_name, record_key) VALUES ('POLICIES', 'NEWER')");
    ReverseSyncTarget reverseTarget = newReverseTarget();
    try {
      ReverseSyncService service = enabledService(reverseTarget);
      assertThat(service.pruneChangeLog()).isEqualTo(1);
      assertThat(
              target.queryForList(
                  "SELECT record_key FROM pas.change_log ORDER BY record_key", String.class))
          .containsExactly("NEWER");
      assertThat(metrics.counter("pas.sync.reverse.change_log.pruned").count()).isEqualTo(1);
    } finally {
      reverseTarget.close();
    }
  }

  private ReverseSyncTarget newReverseTarget() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(SOURCE.getJdbcUrl());
    config.setUsername(SOURCE.getUsername());
    config.setPassword(SOURCE.getPassword());
    config.setMaximumPoolSize(1);
    config.setPoolName("reverse-sync-it");
    return new ReverseSyncTarget(new HikariDataSource(config));
  }

  private ReverseSyncService enabledService(ReverseSyncTarget reverseTarget) {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("reverseSyncTarget", reverseTarget);
    ObjectProvider<ReverseSyncTarget> targetProvider =
        beanFactory.getBeanProvider(ReverseSyncTarget.class);
    return new ReverseSyncService(
        target,
        source,
        targetProvider,
        withReverseSyncEnabled(),
        cipher,
        metrics,
        targetTransactionManager());
  }

  private PlatformTransactionManager targetTransactionManager() {
    return new DataSourceTransactionManager(Objects.requireNonNull(target.getDataSource()));
  }
}
