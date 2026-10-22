package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.reverse.ReverseSyncService;
import com.acme.insurance.pas.batch.migration.source.ReverseSyncTarget;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ReverseSyncIT extends MigrationIntegrationTestSupport {
  @Test
  void disabledByDefaultAndEnabledDrainIsIdempotentAndDecryptsPii() throws Exception {
    seedSource(1);
    bulkLoadAll();
    StaticListableBeanFactory emptyFactory = new StaticListableBeanFactory();
    ObjectProvider<ReverseSyncTarget> emptyProvider =
        emptyFactory.getBeanProvider(ReverseSyncTarget.class);
    ReverseSyncService disabled =
        new ReverseSyncService(target, source, emptyProvider, properties, cipher, metrics);
    assertThat(disabled.drain()).isZero();

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
            target, source, targetProvider, withReverseSyncEnabled(), cipher, metrics);

    assertThat(enabled.drain()).isGreaterThan(0);
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
                "SELECT tax_id FROM acmeins.policy_holders WHERE cust_id = 'C000000001'",
                String.class))
        .isEqualTo("TAX0000001");
    reverseTarget.close();
  }
}
