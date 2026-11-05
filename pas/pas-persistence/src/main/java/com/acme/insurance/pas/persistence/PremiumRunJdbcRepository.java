package com.acme.insurance.pas.persistence;

import static com.acme.insurance.pas.persistence.Columns.date;
import static com.acme.insurance.pas.persistence.Columns.decimal;
import static com.acme.insurance.pas.persistence.Columns.text;

import com.acme.insurance.pas.domain.Premium;
import java.sql.Date;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Data access of the premium recalculation job (AB-523, replacing PREMBAT): the active-policy book
 * it reads from ACMEINS.POLICIES, the PREMIUMS rows it writes and the per-policy outcomes it keeps
 * in PASAPP.PREMIUM_RUN_ITEM. All SQL is portable between PostgreSQL and DB2 for z/OS.
 */
public class PremiumRunJdbcRepository {

  /** PREMBAT {@code POL_CURSOR}: every policy with status 'AC', in policy-number order. */
  public static final String ACTIVE_POLICY_COLUMNS =
      "policy_number, policy_type, effective_date, expiry_date";

  public static final String ACTIVE_POLICY_TABLE = "acmeins.policies";
  public static final String ACTIVE_POLICY_FILTER = "policy_status = 'AC'";
  public static final String SORT_KEY = "policy_number";

  public static final RowMapper<ActivePolicy> ACTIVE_POLICY =
      (rs, row) ->
          new ActivePolicy(
              text(rs, "policy_number"),
              text(rs, "policy_type"),
              date(rs, "effective_date"),
              date(rs, "expiry_date"));

  private static final String UPDATE_PREMIUM =
      """
      UPDATE acmeins.premiums
         SET term_expiry_date = ?, base_rate = ?, territory_factor = ?, class_factor = ?,
             experience_mod = ?, schedule_mod = ?, discount_pct = ?, surcharge_amt = ?,
             tax_amt = ?, total_premium = ?, installment_code = ?, calc_date = ?, calc_by = ?
       WHERE policy_number = ? AND coverage_seq = ? AND term_effective_date = ?
      """;

  private static final String INSERT_PREMIUM =
      """
      INSERT INTO acmeins.premiums (term_expiry_date, base_rate, territory_factor, class_factor,
          experience_mod, schedule_mod, discount_pct, surcharge_amt, tax_amt, total_premium,
          installment_code, calc_date, calc_by,
          policy_number, coverage_seq, term_effective_date)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbc;

  public PremiumRunJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long countActivePolicies() {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + ACTIVE_POLICY_TABLE + " WHERE " + ACTIVE_POLICY_FILTER,
            Long.class);
    return count == null ? 0 : count;
  }

  /**
   * The first policy number of each of at most {@code partitions} contiguous, equally sized ranges
   * of the active book, ascending. Range {@code i} is {@code [starts[i], starts[i + 1])}; the last
   * range is open-ended. Empty when there is no active policy.
   */
  public List<String> partitionStarts(int partitions) {
    if (partitions < 1) {
      throw new IllegalArgumentException("partitions must be at least 1");
    }
    long count = countActivePolicies();
    if (count == 0) {
      return List.of();
    }
    long size = (count + partitions - 1) / partitions;
    return jdbc
        .queryForList(
            "SELECT policy_number FROM (SELECT policy_number,"
                + " ROW_NUMBER() OVER (ORDER BY policy_number) AS rn FROM "
                + ACTIVE_POLICY_TABLE
                + " WHERE "
                + ACTIVE_POLICY_FILTER
                + ") ranked WHERE MOD(rn - 1, ?) = 0 ORDER BY policy_number",
            String.class,
            size)
        .stream()
        .map(String::stripTrailing)
        .toList();
  }

  /**
   * Writes each premium as the row of its key {@code (policy_number, coverage_seq,
   * term_effective_date)}: an existing row is replaced, a missing one inserted. A same-day rerun
   * therefore rewrites the rows instead of failing on the primary key (Q-26). Like the PREMBAT
   * INSERT, {@code installment_amt} is never written: new rows get the column default and a rerun
   * keeps the existing amount.
   */
  public void upsertPremiums(List<Premium> premiums) {
    if (premiums.isEmpty()) {
      return;
    }
    int[] updated = jdbc.batchUpdate(UPDATE_PREMIUM, rows(premiums));
    List<Premium> missing = new ArrayList<>();
    for (int i = 0; i < premiums.size(); i++) {
      int count = updated[i];
      if (count == 0
          || (count == Statement.SUCCESS_NO_INFO
              && jdbc.update(UPDATE_PREMIUM, row(premiums.get(i))) == 0)) {
        missing.add(premiums.get(i));
      }
    }
    if (!missing.isEmpty()) {
      jdbc.batchUpdate(INSERT_PREMIUM, rows(missing));
    }
  }

  /** Records the outcomes of a run, replacing an earlier outcome of the same policy in that run. */
  public void recordOutcomes(long runId, LocalDate businessDate, List<PremiumRunItem> items) {
    if (items.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        "DELETE FROM pasapp.premium_run_item WHERE job_instance_id = ? AND policy_number = ?",
        items.stream().map(i -> new Object[] {runId, i.policyNumber()}).toList());
    jdbc.batchUpdate(
        """
        INSERT INTO pasapp.premium_run_item
            (job_instance_id, policy_number, business_date, status, total_premium, error_code, detail)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        items.stream()
            .map(
                i ->
                    new Object[] {
                      runId,
                      i.policyNumber(),
                      Date.valueOf(businessDate),
                      i.status().code(),
                      i.totalPremium(),
                      i.errorCode(),
                      i.detail()
                    })
            .toList());
  }

  public PremiumRunTotals totals(long runId) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*) AS read_count,
               COALESCE(SUM(CASE WHEN status = 'U' THEN 1 ELSE 0 END), 0) AS updated_count,
               COALESCE(SUM(CASE WHEN status = 'E' THEN 1 ELSE 0 END), 0) AS error_count
          FROM pasapp.premium_run_item WHERE job_instance_id = ?
        """,
        (rs, row) ->
            new PremiumRunTotals(
                rs.getLong("read_count"), rs.getLong("updated_count"), rs.getLong("error_count")),
        runId);
  }

  /** Streams the outcomes of a run in policy-number order (the PREMRPT detail order). */
  public void streamOutcomes(long runId, Consumer<PremiumRunItem> sink) {
    jdbc.query(
        """
        SELECT policy_number, status, total_premium, error_code, detail
          FROM pasapp.premium_run_item WHERE job_instance_id = ? ORDER BY policy_number
        """,
        rs -> {
          sink.accept(
              new PremiumRunItem(
                  text(rs, "policy_number"),
                  PremiumRunItem.Status.of(text(rs, "status")),
                  decimal(rs, "total_premium"),
                  text(rs, "error_code"),
                  text(rs, "detail")));
        },
        runId);
  }

  private static List<Object[]> rows(List<Premium> premiums) {
    return premiums.stream().map(PremiumRunJdbcRepository::row).toList();
  }

  private static Object[] row(Premium p) {
    return new Object[] {
      sqlDate(p.termExpiryDate()),
      p.baseRate(),
      p.territoryFactor(),
      p.classFactor(),
      p.experienceMod(),
      p.scheduleMod(),
      p.discountPct(),
      p.surchargeAmt(),
      p.taxAmt(),
      p.totalPremium(),
      p.installmentCode(),
      sqlDate(p.calcDate()),
      p.calcBy(),
      p.policyNumber(),
      p.coverageSeq(),
      sqlDate(p.termEffectiveDate())
    };
  }

  private static Date sqlDate(LocalDate date) {
    return date == null ? null : Date.valueOf(date);
  }
}
