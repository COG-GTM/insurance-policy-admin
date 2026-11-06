package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class MigrationAlertTest {

  @Test
  void databaseFailuresAreReducedToSqlStateAndVendorCode() {
    SQLException driver =
        new SQLException(
            "ERROR: null value violates not-null constraint. Detail: Failing row contains"
                + " (C0001, 1234, 999-00-0000, 1970-01-01)",
            "23502",
            -407);
    String reason =
        MigrationJobsConfiguration.alertReason(
            new DataIntegrityViolationException("INSERT failed; " + driver.getMessage(), driver));

    assertThat(reason)
        .isEqualTo("SQLSTATE 23502 vendor code -407 (SQLException)")
        .doesNotContain("Failing row", "999-00-0000", "1970");
  }

  @Test
  void ownGuardMessagesAreKept() {
    String reason =
        MigrationJobsConfiguration.alertReason(
            new IllegalStateException("system of record changed to PG during the sync; stopped"));

    assertThat(reason).contains("system of record changed to PG");
  }

  @Test
  void otherFailuresKeepOnlyTheirClasses() {
    String reason =
        MigrationJobsConfiguration.alertReason(
            new RuntimeException("value 999-00-0000", new IllegalArgumentException("x")));

    assertThat(reason).isEqualTo("RuntimeException (IllegalArgumentException)");
  }
}
