package com.acme.insurance.pas.batch.migration;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public final class MigrationTlsStartupGuard {
  private final MigrationProperties properties;
  private final Environment environment;

  public MigrationTlsStartupGuard(MigrationProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
    validate();
  }

  private void validate() {
    if (!properties.enabled() || environment.acceptsProfiles(Profiles.of("local", "test", "it"))) {
      return;
    }
    if (!properties.source().ssl()) {
      throw new IllegalStateException(
          "pas.migration.source.ssl must be true outside local/test profiles");
    }
    if (properties.source().sslTrustStoreLocation() == null
        || properties.source().sslTrustStoreLocation().isBlank()) {
      throw new IllegalStateException(
          "pas.migration.source.ssl-trust-store-location is required outside local/test profiles");
    }
  }
}
