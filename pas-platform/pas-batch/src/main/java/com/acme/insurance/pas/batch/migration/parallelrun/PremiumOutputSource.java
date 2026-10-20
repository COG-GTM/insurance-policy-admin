package com.acme.insurance.pas.batch.migration.parallelrun;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * PREMIUMS rows for one business date, keyed by {@code
 * POLICY_NUMBER|COVERAGE_SEQ|TERM_EFFECTIVE_DATE} with the {@link
 * com.acme.insurance.pas.batch.migration.reconcile.Canonical} record as value. Empty means the
 * output was not produced for that date.
 */
public interface PremiumOutputSource {

  String location();

  Optional<Map<String, String>> premiums(LocalDate businessDate);
}
