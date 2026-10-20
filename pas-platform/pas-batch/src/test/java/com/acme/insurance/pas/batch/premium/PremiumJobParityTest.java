package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import com.acme.insurance.pas.parity.ParityHarness;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

/**
 * AB-523 acceptance: on the PREMBAT fixtures, premiumRecalculationJob writes the PREMIUMS rows and
 * report totals of the COBOL run ({@code parity/programs/prembat/golden.csv}).
 */
class PremiumJobParityTest extends PremiumJobTestSupport {

  static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 20);

  @Test
  void premiumsRowsAndReportTotalsMatchPrembatGoldens() throws Exception {
    resetBook();
    Map<String, String> policyByCase = seedFixtures("prembat");
    // POL_CURSOR selects POLICY_STATUS = 'AC' only: these must be neither read nor written
    insertPolicy("PRB900000001", "AUT", "PN", RUN_DATE, RUN_DATE.plusYears(1));
    insertPolicy("PRB900000002", "HOM", "CN", RUN_DATE, RUN_DATE.plusYears(1));
    insertPolicy("PRB900000003", "COM", "ac", RUN_DATE, RUN_DATE.plusYears(1));

    JobExecution execution = run(RUN_DATE, false);

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    ParityHarness.assertParity(new JobOutputAdapter(policyByCase));

    int goldenRows = policyByCase.size();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isEqualTo(goldenRows);
    // PREMBAT stub run: every INSERT succeeds, so READ = UPDATED = golden rows, ERRORS = 0
    PremiumRunSummary summary = summaries.find(execution.getId()).orElseThrow();
    assertThat(summary.totals()).isEqualTo(new PremiumRunTotals(goldenRows, goldenRows, 0));
    assertThat(summary.outcome()).isEqualTo(PremiumRunOutcome.COMPLETED);
    assertThat(summary.ratingVersion()).isEqualTo("PREMBAT-3200/PARITY");
    assertThat(reportTrailer(summary.reportPath()))
        .containsExactly(
            String.format("TOTAL POLICIES READ:    %07d", goldenRows),
            String.format("TOTAL POLICIES UPDATED: %07d", goldenRows),
            "TOTAL ERRORS:           0000000");
    List<String> report = Files.readAllLines(Path.of(summary.reportPath()));
    assertThat(report.get(0))
        .isEqualTo("ACME INSURANCE - PREMIUM BATCH CALCULATION RUN DATE: 20261020");
    assertThat(report.get(2)).isEqualTo("PRB000000001 PREMIUM: 904.75");
    assertThat(report.subList(2, 2 + goldenRows)).isSorted();
  }

  /** Reads back the PREMIUMS row the job wrote for each fixture case. */
  final class JobOutputAdapter implements ParityAdapter {
    private final Map<String, String> policyByCase;

    JobOutputAdapter(Map<String, String> policyByCase) {
      this.policyByCase = policyByCase;
    }

    @Override
    public String program() {
      return "prembat";
    }

    @Override
    public Map<String, String> run(ParityCase c) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "SELECT * FROM pas.premiums WHERE policy_number = ?", policyByCase.get(c.caseId()));
      BigDecimal base = (BigDecimal) row.get("base_rate");
      // PREMIUMS stores factors, not PREMBAT's intermediate WS- amounts; recompute them from the
      // stored factors (truncating, as the COBOL COMPUTEs) so the golden columns are still checked
      BigDecimal territory = truncate(base.multiply((BigDecimal) row.get("territory_factor")));
      BigDecimal clazz = truncate(territory.multiply((BigDecimal) row.get("class_factor")));
      BigDecimal mod = truncate(clazz.multiply((BigDecimal) row.get("experience_mod")));
      assertThat(row.get("calc_by").toString().trim()).isEqualTo("PREMBAT");
      assertThat(row.get("calc_date").toString()).isEqualTo(RUN_DATE.toString());
      assertThat(row.get("installment_code")).isEqualTo("AN");
      Map<String, String> out = new LinkedHashMap<>();
      out.put("coverage_seq", ParityFormat.integer(((Number) row.get("coverage_seq")).longValue()));
      out.put(
          "term_eff_date",
          ParityFormat.date(((java.sql.Date) row.get("term_effective_date")).toLocalDate()));
      out.put(
          "term_exp_date",
          ParityFormat.date(((java.sql.Date) row.get("term_expiry_date")).toLocalDate()));
      out.put("base_rate", ParityFormat.decimal(base, 4));
      out.put("territory_premium", ParityFormat.money(territory));
      out.put("class_premium", ParityFormat.money(clazz));
      out.put("mod_premium", ParityFormat.money(mod));
      out.put("tax_amt", ParityFormat.money((BigDecimal) row.get("tax_amt")));
      out.put("surcharge_amt", ParityFormat.money((BigDecimal) row.get("surcharge_amt")));
      out.put("total_premium", ParityFormat.money((BigDecimal) row.get("total_premium")));
      return out;
    }

    private static BigDecimal truncate(BigDecimal v) {
      return v.setScale(2, RoundingMode.DOWN);
    }
  }
}
