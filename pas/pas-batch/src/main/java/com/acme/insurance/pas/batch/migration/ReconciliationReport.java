package com.acme.insurance.pas.batch.migration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of one reconciliation: aggregate checks and record-level differences (keys only, never
 * column values or hashes).
 */
public record ReconciliationReport(
    LocalDate businessDate,
    List<Check> checks,
    List<Difference> differences,
    long differenceCount) {

  /** Kind of check, as stored in acmeins_shadow.recon_check. */
  public enum Kind {
    COUNT,
    TOTAL,
    HASH,
    ORPHAN
  }

  /** Kind of record difference, as stored in acmeins_shadow.recon_difference. */
  public enum DifferenceKind {
    MISSING_IN_PG,
    MISSING_IN_DB2,
    HASH_MISMATCH,
    DUPLICATE_KEY
  }

  /** One aggregate comparison. */
  public record Check(
      Kind kind,
      MigrationTable table,
      String dimension,
      String metric,
      BigDecimal db2Value,
      BigDecimal pgValue) {

    public boolean matches() {
      return db2Value != null && pgValue != null && db2Value.compareTo(pgValue) == 0;
    }

    public String id() {
      return kind + ":" + table + ":" + dimension + ":" + metric;
    }
  }

  /** One record that differs between the two sides. */
  public record Difference(MigrationTable table, String recordKey, DifferenceKind kind) {}

  public ReconciliationReport {
    checks = List.copyOf(checks);
    differences = List.copyOf(differences);
  }

  public boolean matches() {
    return differenceCount == 0 && checks.stream().allMatch(Check::matches);
  }

  public long failedChecks() {
    return checks.stream().filter(c -> !c.matches()).count();
  }
}
