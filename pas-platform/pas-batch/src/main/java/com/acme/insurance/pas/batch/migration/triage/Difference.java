package com.acme.insurance.pas.batch.migration.triage;

import java.util.List;

/**
 * One difference between legacy (DB2 / mainframe) and new-platform data or output.
 *
 * @param check {@code RECONCILIATION} or {@code PARALLEL_RUN}
 * @param scope table (e.g. {@code POLICIES}) or output (e.g. {@code
 *     dailyExtractJob/policy_extract.dat})
 * @param kind what differs, e.g. {@code CHANGED}, {@code MISSING_IN_TARGET}, {@code TOTAL_MISMATCH}
 * @param key record or group key ({@code |}-joined key fields)
 * @param fields differing fields (empty when the whole record is missing/extra)
 * @param legacy legacy value(s), canonical form (PII is never included)
 * @param candidate new-platform value(s), canonical form
 * @param classification triage result
 * @param triageId matching triage-register entry id, or {@code null}
 * @param reference quirk-register link (APPROVED_FIX), ticket (BUG) or owner note (DATA_ISSUE)
 */
public record Difference(
    String check,
    String scope,
    String kind,
    String key,
    List<String> fields,
    String legacy,
    String candidate,
    Classification classification,
    String triageId,
    String reference) {

  public Difference {
    fields = fields == null ? List.of() : List.copyOf(fields);
    if (classification == null) {
      classification = Classification.UNCLASSIFIED;
    }
  }

  public static Difference unclassified(
      String check,
      String scope,
      String kind,
      String key,
      List<String> fields,
      String legacy,
      String candidate) {
    return new Difference(
        check,
        scope,
        kind,
        key,
        fields,
        legacy,
        candidate,
        Classification.UNCLASSIFIED,
        null,
        null);
  }

  public Difference classified(Classification value, String entryId, String ref) {
    return new Difference(check, scope, kind, key, fields, legacy, candidate, value, entryId, ref);
  }
}
