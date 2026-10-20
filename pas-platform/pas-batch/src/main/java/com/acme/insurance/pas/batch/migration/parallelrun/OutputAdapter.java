package com.acme.insurance.pas.batch.migration.parallelrun;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One daily output compared in the parallel run: legacy (mainframe) records versus new-platform
 * records for a business date, both keyed by record key. New outputs plug in by adding a bean of
 * this type (e.g. AB-523 premiums through {@link PremiumOutputSource}).
 */
public interface OutputAdapter {

  /** Output name used in reports and the triage register, e.g. {@code PREMIUMS}. */
  String scope();

  /** Whether the output is produced on this business date (monthly outputs run on the 1st). */
  boolean runsOn(LocalDate businessDate);

  /** Legacy records, or empty when the legacy capture for the date is not available. */
  Optional<Records> legacy(LocalDate businessDate) throws IOException;

  /** New-platform records, or empty when the new output was not produced. */
  Optional<Records> candidate(LocalDate businessDate) throws IOException;

  /** Names of the fields that differ between two records with the same key. */
  List<String> changedFields(String legacy, String candidate);

  /** Records of one side plus where they were read from. */
  record Records(String location, Map<String, String> byKey) {
    public Records {
      byKey = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(byKey));
    }
  }
}
