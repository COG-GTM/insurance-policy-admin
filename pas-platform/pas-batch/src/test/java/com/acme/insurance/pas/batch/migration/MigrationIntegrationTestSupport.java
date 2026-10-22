package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.bulk.MigrationRow;
import com.acme.insurance.pas.batch.migration.bulk.MigrationRowReader;
import com.acme.insurance.pas.batch.migration.bulk.MigrationRowWriter;
import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.pii.BlindIndex;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiCrypto;
import com.acme.insurance.pas.pii.PiiKey;
import com.acme.insurance.pas.pii.PiiKeyRing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
abstract class MigrationIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("postgres:16-alpine");

  static JdbcTemplate target;
  static JdbcTemplate sourceJdbc;
  static Db2Source source;
  static MigrationProperties properties;
  static PiiCipher cipher;
  static BlindIndex blindIndex;
  static SimpleMeterRegistry metrics;
  static MigrationRowWriter writer;

  @BeforeAll
  static void initializeMigrationDatabases() throws IOException {
    TARGET.start();
    SOURCE.start();
    Flyway.configure()
        .dataSource(TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword())
        .locations("classpath:db/pas-migration")
        .schemas("pas")
        .defaultSchema("pas")
        .load()
        .migrate();
    Flyway.configure()
        .dataSource(TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword())
        .locations("classpath:db/migration/batch")
        .schemas("pas_batch")
        .defaultSchema("pas_batch")
        .load()
        .migrate();
    new ResourceDatabasePopulator(new ClassPathResource("migration/acmeins-ddl.sql"))
        .execute(
            new DriverManagerDataSource(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword()));
    target =
        new JdbcTemplate(
            new DriverManagerDataSource(
                TARGET.getJdbcUrl(), TARGET.getUsername(), TARGET.getPassword()));
    sourceJdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword()));
    String reconKey = encodedKey(3);
    properties =
        new MigrationProperties(
            true,
            new MigrationProperties.Source(
                SOURCE.getJdbcUrl(),
                SOURCE.getUsername(),
                SOURCE.getPassword(),
                "acmeins",
                false,
                null,
                "America/New_York",
                100),
            new MigrationProperties.Bulk(1, 10),
            new MigrationProperties.DeltaSync(
                Duration.ofMinutes(5), false, Duration.ofHours(1), false, Duration.ofMinutes(1)),
            new MigrationProperties.ReverseSync(
                false, 8, SOURCE.getUsername(), SOURCE.getPassword()),
            new MigrationProperties.Recon(
                false, "0 0 3 * * *", System.getProperty("java.io.tmpdir"), reconKey),
            new MigrationProperties.ParallelRun(
                System.getProperty("java.io.tmpdir"), System.getProperty("java.io.tmpdir")));
    PiiCrypto crypto =
        PiiCrypto.create(
            new PiiKeyRing(new PiiKey("migration-it", 1, secretKey(1, "AES")), List.of()),
            secretKey(2, "HmacSHA256"));
    cipher = crypto.cipher();
    blindIndex = crypto.blindIndex();
    metrics = new SimpleMeterRegistry();
    source = new Db2Source(properties);
    writer = new MigrationRowWriter(target, cipher, blindIndex, properties, metrics);
  }

  @AfterAll
  static void closeMigrationResources() {
    if (source != null) {
      source.close();
    }
    if (metrics != null) {
      metrics.close();
    }
  }

  @BeforeEach
  void clearMigrationFixtures() {
    sourceJdbc.execute(
        "TRUNCATE acmeins.territory_factors, acmeins.underwriting_decisions, "
            + "acmeins.endorsements, acmeins.premiums, acmeins.coverages, acmeins.policies, "
            + "acmeins.policy_holders CASCADE");
    target.execute(
        "TRUNCATE pas.underwriting_decisions, pas.endorsements, pas.premiums, pas.coverages, "
            + "pas.policies, pas.policy_holders, pas.territory_factors CASCADE");
    target.execute(
        "TRUNCATE pas.change_log, pas_batch.sync_watermark, "
            + "pas_batch.recon_difference, pas_batch.recon_run, "
            + "pas_batch.parallel_run_triage_log, pas_batch.parallel_run_difference, "
            + "pas_batch.parallel_run_date, pas_batch.parallel_run_cycle CASCADE");
    target.execute("TRUNCATE pas_shadow.premiums");
  }

  void seedSource(int policyCount) {
    AcmeinsBookGenerator.populate(sourceJdbc, policyCount);
  }

  void bulkLoadAll() throws Exception {
    for (MigrationTable table : MigrationTable.bulkLoadOrder()) {
      for (Db2Source.KeyRange range : source.keyRanges(table, 1)) {
        MigrationRowReader reader =
            new MigrationRowReader(source, table, range.minimum(), range.maximum(), 100);
        org.springframework.batch.item.ExecutionContext context =
            new org.springframework.batch.item.ExecutionContext();
        reader.open(context);
        MigrationRow row;
        while ((row = reader.read()) != null) {
          writer.write(new org.springframework.batch.item.Chunk<>(List.of(row)));
          reader.update(context);
        }
        reader.close();
      }
    }
  }

  MigrationProperties withReverseSyncEnabled() {
    return new MigrationProperties(
        properties.enabled(),
        properties.source(),
        properties.bulk(),
        properties.deltaSync(),
        new MigrationProperties.ReverseSync(true, 8, SOURCE.getUsername(), SOURCE.getPassword()),
        properties.recon(),
        properties.parallelRun());
  }

  static String encodedKey(int seed) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) seed);
    return Base64.getEncoder().encodeToString(bytes);
  }

  static SecretKey secretKey(int seed, String algorithm) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) seed);
    return new SecretKeySpec(bytes, algorithm);
  }
}
