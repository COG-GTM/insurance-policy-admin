package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MigrationPropertiesTest {
  @Test
  void rejectsInvalidSourceSchema() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new MigrationProperties.Source(
                    "jdbc:db2://host:50000/acmeins",
                    "user",
                    "password",
                    "ACMEINS;DROP TABLE PAS.POLICIES",
                    true,
                    "/truststore",
                    "UTC",
                    100))
        .withMessage("pas.migration.source.schema is not a valid identifier");
  }
}
