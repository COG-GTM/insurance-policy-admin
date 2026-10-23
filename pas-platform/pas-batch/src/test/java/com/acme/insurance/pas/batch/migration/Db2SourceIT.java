package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.source.Db2Source;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db2")
class Db2SourceIT {
  @Test
  void readsCurrentTimestampFromConfiguredDb2Instance() {
    String url = System.getenv("PAS_DB2_IT_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "PAS_DB2_IT_URL is not configured");
    MigrationProperties properties =
        new MigrationProperties(
            true,
            new MigrationProperties.Source(
                url,
                System.getenv("PAS_DB2_IT_USERNAME"),
                System.getenv("PAS_DB2_IT_PASSWORD"),
                System.getenv().getOrDefault("PAS_DB2_IT_SCHEMA", "ACMEINS"),
                Boolean.parseBoolean(System.getenv().getOrDefault("PAS_DB2_IT_SSL", "true")),
                System.getenv("PAS_DB2_IT_SSL_TRUST_STORE"),
                "America/New_York",
                100),
            null,
            null,
            null,
            null,
            null);
    try (Db2Source source = new Db2Source(properties)) {
      LocalDateTime now = source.currentTimestamp();
      assertThat(now).isNotNull();
    }
  }
}
