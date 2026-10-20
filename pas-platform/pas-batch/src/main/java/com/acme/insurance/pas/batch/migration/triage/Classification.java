package com.acme.insurance.pas.batch.migration.triage;

/**
 * Triage outcome of one reconciliation or parallel-run difference. Unexplained differences are
 * {@link #BUG} plus {@link #UNCLASSIFIED}; a cycle passes only when that count is zero.
 */
public enum Classification {
  /** New platform is wrong; must be fixed (and re-diffed) before cutover. */
  BUG,
  /** Intentional behaviour change linked to a quirk-register decision ({@code #q-nn}). */
  APPROVED_FIX,
  /** Source data problem (bad/legacy DB2 data, timing), not a code defect; has an owner. */
  DATA_ISSUE,
  /** Not yet triaged. Default for every new difference. */
  UNCLASSIFIED;

  public boolean unexplained() {
    return this == BUG || this == UNCLASSIFIED;
  }
}
