package com.acme.insurance.pas.batch.migration.parallelrun;

import com.acme.insurance.pas.batch.migration.MigrationTable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parallel-run comparison of PREMBAT PREMIUMS rows (legacy, {@link JdbcPremiumOutputSource}) with
 * the AB-523 premiumRecalculationJob shadow output ({@link PremiumJobOutputSource}).
 */
public final class PremiumRowsAdapter implements OutputAdapter {

  public static final String SCOPE = "PREMIUMS";

  private final PremiumOutputSource legacy;
  private final PremiumOutputSource candidate;

  public PremiumRowsAdapter(PremiumOutputSource legacy, PremiumOutputSource candidate) {
    this.legacy = legacy;
    this.candidate = candidate;
  }

  @Override
  public String scope() {
    return SCOPE;
  }

  @Override
  public boolean runsOn(LocalDate businessDate) {
    return true;
  }

  @Override
  public Optional<Records> legacy(LocalDate businessDate) {
    return legacy.premiums(businessDate).map(m -> new Records(legacy.location(), m));
  }

  @Override
  public Optional<Records> candidate(LocalDate businessDate) {
    return candidate.premiums(businessDate).map(m -> new Records(candidate.location(), m));
  }

  @Override
  public List<String> changedFields(String a, String b) {
    String[] x = a.split("(?<!\\\\)\\|", -1);
    String[] y = b.split("(?<!\\\\)\\|", -1);
    List<String> columns = MigrationTable.PREMIUMS.columns();
    List<String> changed = new ArrayList<>();
    for (int i = 0; i < columns.size(); i++) {
      if (i >= x.length || i >= y.length || !x[i].equals(y[i])) {
        changed.add(columns.get(i).toUpperCase(Locale.ROOT));
      }
    }
    return changed;
  }
}
