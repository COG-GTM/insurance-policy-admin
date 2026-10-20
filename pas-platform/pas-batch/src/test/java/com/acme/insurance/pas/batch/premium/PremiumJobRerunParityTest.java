package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.parity.ParityAdapter;
import com.acme.insurance.pas.parity.ParityCase;
import com.acme.insurance.pas.parity.ParityFormat;
import com.acme.insurance.pas.parity.ParityHarness;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

/**
 * Second run in the same term ({@code parity/programs/prembat-rerun}, quirk Q-22) — here the next
 * day's scheduled run, which collides on the same PREMIUMS key. COBOL: the INSERT gets -803, the
 * row is kept, the policy counts as an error, RC 4. Java: the row is replaced and counted as
 * updated. The write result is the only accepted difference (parity/known-differences.json).
 */
class PremiumJobRerunParityTest extends PremiumJobTestSupport {

  static final LocalDate FIRST_RUN = LocalDate.of(2026, 10, 25);
  static final LocalDate SECOND_RUN = LocalDate.of(2026, 10, 26);

  @Test
  void secondRunInTheSameTermReplacesRowsWhereCobolFailedWithDuplicateKey() throws Exception {
    resetBook();
    Map<String, String> policyByCase = seedFixtures("prembat-rerun");
    assertThat(run(FIRST_RUN, false).getStatus()).isEqualTo(BatchStatus.COMPLETED);

    JobExecution second = run(SECOND_RUN, false);

    assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(PremiumRunOutcome.of(second).exitCode()).isZero();
    int cases = policyByCase.size();
    // COBOL on the same data: UPDATED = 0, ERRORS = 1, RC 4 (golden premiums_sqlcode -803)
    assertThat(summaries.find(second.getId()).orElseThrow().totals())
        .isEqualTo(new PremiumRunTotals(cases, cases, 0));
    ParityHarness.assertParity(
        new ParityAdapter() {
          @Override
          public String program() {
            return "prembat-rerun";
          }

          @Override
          public Map<String, String> run(ParityCase c) {
            Map<String, Object> row =
                jdbc.queryForMap(
                    "SELECT * FROM pas.premiums WHERE policy_number = ?",
                    policyByCase.get(c.caseId()));
            boolean replaced = SECOND_RUN.toString().equals(row.get("calc_date").toString());
            Map<String, String> out = new LinkedHashMap<>();
            out.put(
                "term_eff_date",
                ParityFormat.date(((java.sql.Date) row.get("term_effective_date")).toLocalDate()));
            out.put("total_premium", ParityFormat.money((BigDecimal) row.get("total_premium")));
            // the result the COBOL INSERT would see: 0 = row written (here: replaced)
            out.put("premiums_sqlcode", replaced ? "0" : "-803");
            return out;
          }
        });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pas.premiums", Integer.class))
        .isEqualTo(cases);
  }
}
