package com.acme.insurance.pas.batch.migration.reconcile;

import com.acme.insurance.pas.batch.migration.MigrationTable;
import com.acme.insurance.pas.batch.migration.SourceDatabase;
import com.acme.insurance.pas.batch.migration.report.CheckReport;
import com.acme.insurance.pas.batch.migration.report.CheckType;
import com.acme.insurance.pas.batch.migration.triage.Difference;
import com.acme.insurance.pas.batch.migration.triage.TriageRegister;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Daily DB2 ↔ {@code pas} reconciliation: row counts for every migrated table, premium and limit
 * totals by policy type and branch, and record-level SHA-256 hashes ({@link Canonical}) for
 * POLICIES, COVERAGES and PREMIUMS. Every difference is classified through the {@link
 * TriageRegister}.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "shares Spring-managed collaborators (JDBC, services) by design")
public final class ReconciliationService {

  public static final String CHECK = CheckType.RECONCILIATION.name();
  static final List<MigrationTable> HASHED = List.of(MigrationTable.values());

  /** Row count of one table on each side. */
  public record RowCount(String table, long source, long target, boolean match) {}

  /** One grouped total. {@code metric} is {@code TABLE.COLUMN} or {@code TABLE.ROWS}. */
  public record Total(
      String metric,
      String policyType,
      String branch,
      String source,
      String target,
      boolean match) {}

  /** Record-hash comparison summary for one table; digests are over the sorted key→hash list. */
  public record HashSummary(
      String table,
      long sourceRows,
      long targetRows,
      long matched,
      long missingInTarget,
      long extraInTarget,
      long changed,
      String sourceDigest,
      String targetDigest) {}

  private static final List<String[]> TOTALS =
      List.of(
          new String[] {"POLICIES", "TOTAL_PREMIUM", null},
          new String[] {"POLICIES", "COVERAGE_LIMIT", null},
          new String[] {"COVERAGES", "PREMIUM", "COVERAGES"},
          new String[] {"COVERAGES", "COVERAGE_LIMIT", "COVERAGES"},
          new String[] {"PREMIUMS", "TOTAL_PREMIUM", "PREMIUMS"});

  private final SourceDatabase source;
  private final JdbcTemplate target;
  private final TriageRegister triage;
  private final int sampleLimit;
  private final com.acme.insurance.pas.batch.migration.PolicyHolderPii pii;
  private final Clock clock;

  public ReconciliationService(
      SourceDatabase source,
      JdbcTemplate target,
      TriageRegister triage,
      com.acme.insurance.pas.batch.migration.PolicyHolderPii pii,
      int sampleLimit,
      Clock clock) {
    this.pii = pii;
    this.source = source;
    this.target = target;
    this.triage = triage;
    this.sampleLimit = sampleLimit;
    this.clock = clock;
  }

  public CheckReport reconcile(LocalDate businessDate, String runId) {
    Instant started = clock.instant();
    List<Difference> differences = new ArrayList<>();
    List<RowCount> counts = rowCounts(differences);
    List<Total> totals = totals(differences);
    List<HashSummary> hashes = new ArrayList<>();
    for (MigrationTable table : HASHED) {
      hashes.add(hashes(table, differences));
    }
    Map<String, Object> sections = new LinkedHashMap<>();
    sections.put("rowCounts", counts);
    sections.put("totals", totals);
    sections.put("recordHashes", hashes);
    List<String> provenance =
        List.of(
            "source: " + source.table("*") + " via JDBC (read-only)",
            "target: pas.* (PostgreSQL)",
            "TERRITORY_FACTORS not migrated (Q-03 / OQ-04); POLICY_HOLDERS PII is encrypted in the"
                + " target and compared by row count only");
    return CheckReport.of(
        runId,
        CheckType.RECONCILIATION,
        businessDate,
        started,
        clock.instant(),
        true,
        provenance,
        sections,
        triage.classifyAll(differences));
  }

  private List<RowCount> rowCounts(List<Difference> differences) {
    List<RowCount> counts = new ArrayList<>();
    for (MigrationTable table : MigrationTable.values()) {
      long src = count(source.jdbc(), source.table(table.sourceTable()));
      long tgt = count(target, table.targetTable());
      counts.add(new RowCount(table.sourceTable(), src, tgt, src == tgt));
      if (src != tgt) {
        differences.add(
            Difference.unclassified(
                CHECK,
                table.sourceTable(),
                "COUNT_MISMATCH",
                "*",
                List.of("ROWS"),
                Long.toString(src),
                Long.toString(tgt)));
      }
    }
    return counts;
  }

  private static long count(JdbcTemplate jdbc, String table) {
    Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    return n == null ? 0 : n;
  }

  private List<Total> totals(List<Difference> differences) {
    Map<String, BigDecimal> src = new TreeMap<>();
    Map<String, BigDecimal> tgt = new TreeMap<>();
    for (String[] metric : TOTALS) {
      collectTotals(source.jdbc(), source::table, metric, src);
      collectTotals(target, t -> "pas." + t.toLowerCase(java.util.Locale.ROOT), metric, tgt);
    }
    TreeSet<String> keys = new TreeSet<>(src.keySet());
    keys.addAll(tgt.keySet());
    List<Total> totals = new ArrayList<>();
    for (String k : keys) {
      String[] parts = k.split("\u0000", -1);
      BigDecimal s = src.get(k);
      BigDecimal t = tgt.get(k);
      boolean match = s != null && t != null && s.compareTo(t) == 0;
      String sText = s == null ? Canonical.NULL : s.toPlainString();
      String tText = t == null ? Canonical.NULL : t.toPlainString();
      totals.add(new Total(parts[0], parts[1], parts[2], sText, tText, match));
      if (!match) {
        differences.add(
            Difference.unclassified(
                CHECK,
                parts[0].substring(0, parts[0].indexOf('.')),
                "TOTAL_MISMATCH",
                parts[1] + "|" + parts[2],
                List.of(parts[0]),
                sText,
                tText));
      }
    }
    return totals;
  }

  /** Adds {@code TABLE.COLUMN} sums and {@code TABLE.ROWS} counts by policy type and branch. */
  private static void collectTotals(
      JdbcTemplate jdbc,
      Function<String, String> table,
      String[] metric,
      Map<String, BigDecimal> out) {
    String fact = metric[0];
    String column = metric[1];
    String from =
        metric[2] == null
            ? table.apply("POLICIES") + " p"
            : table.apply(metric[2])
                + " f JOIN "
                + table.apply("POLICIES")
                + " p ON p.POLICY_NUMBER = f.POLICY_NUMBER";
    String measure = (metric[2] == null ? "p." : "f.") + column;
    String sql =
        "SELECT p.POLICY_TYPE, p.BRANCH_CODE, COUNT(*), SUM("
            + measure
            + ") FROM "
            + from
            + " GROUP BY p.POLICY_TYPE, p.BRANCH_CODE";
    jdbc.query(
        sql,
        rs -> {
          String type = Canonical.value("policy_type", rs.getObject(1));
          String branch = Canonical.value("branch_code", rs.getObject(2));
          BigDecimal sum = rs.getBigDecimal(4);
          out.merge(
              fact + "." + column + "\u0000" + type + "\u0000" + branch,
              sum == null ? BigDecimal.ZERO : sum,
              BigDecimal::add);
          out.put(fact + ".ROWS\u0000" + type + "\u0000" + branch, rs.getBigDecimal(3));
        });
  }

  private HashSummary hashes(MigrationTable table, List<Difference> differences) {
    TreeMap<String, String> src = hashMap(source.jdbc(), sourceSelect(table), table, false);
    TreeMap<String, String> tgt = hashMap(target, targetSelect(table), table, true);
    long matched = 0;
    long missing = 0;
    long extra = 0;
    List<String> changed = new ArrayList<>();
    for (Map.Entry<String, String> e : src.entrySet()) {
      String other = tgt.get(e.getKey());
      if (other == null) {
        missing++;
        differences.add(
            Difference.unclassified(
                CHECK,
                table.sourceTable(),
                "MISSING_IN_TARGET",
                e.getKey(),
                List.of(),
                e.getValue(),
                null));
      } else if (other.equals(e.getValue())) {
        matched++;
      } else {
        changed.add(e.getKey());
      }
    }
    for (Map.Entry<String, String> e : tgt.entrySet()) {
      if (!src.containsKey(e.getKey())) {
        extra++;
        differences.add(
            Difference.unclassified(
                CHECK,
                table.sourceTable(),
                "EXTRA_IN_TARGET",
                e.getKey(),
                List.of(),
                null,
                e.getValue()));
      }
    }
    for (int i = 0; i < changed.size(); i++) {
      String key = changed.get(i);
      differences.add(
          i < sampleLimit
              ? fieldDiff(table, key)
              : Difference.unclassified(
                  CHECK,
                  table.sourceTable(),
                  "CHANGED",
                  key,
                  List.of(),
                  src.get(key),
                  tgt.get(key)));
    }
    return new HashSummary(
        table.sourceTable(),
        src.size(),
        tgt.size(),
        matched,
        missing,
        extra,
        changed.size(),
        digest(src),
        digest(tgt));
  }

  private Difference fieldDiff(MigrationTable table, String key) {
    Map<String, Object> s = fetch(source.jdbc(), sourceSelect(table), table, key, false);
    Map<String, Object> t = fetch(target, targetSelect(table), table, key, true);
    List<String> fields = new ArrayList<>();
    List<String> sv = new ArrayList<>();
    List<String> tv = new ArrayList<>();
    for (String column : hashColumns(table)) {
      String a = Canonical.value(column, s.get(column));
      String b = Canonical.value(column, t.get(column));
      if (!Objects.equals(a, b)) {
        fields.add(column.toUpperCase(java.util.Locale.ROOT));
        sv.add(a);
        tv.add(b);
      }
    }
    return Difference.unclassified(
        CHECK,
        table.sourceTable(),
        "CHANGED",
        key,
        fields,
        String.join("|", sv),
        String.join("|", tv));
  }

  private Map<String, Object> fetch(
      JdbcTemplate jdbc, String select, MigrationTable table, String key, boolean isTarget) {
    String[] parts = key.split("(?<!\\\\)\\|", -1);
    StringBuilder where = new StringBuilder();
    List<Object> args = new ArrayList<>();
    for (int i = 0; i < table.keyColumns().size(); i++) {
      String column = table.keyColumns().get(i);
      where.append(i == 0 ? " WHERE " : " AND ");
      if (column.endsWith("_date")) {
        where.append(column.toUpperCase(java.util.Locale.ROOT)).append(" = ?");
        args.add(java.sql.Date.valueOf(parts[i]));
      } else if (column.endsWith("_seq") || column.endsWith("_num")) {
        where.append(column.toUpperCase(java.util.Locale.ROOT)).append(" = ?");
        args.add(Integer.valueOf(parts[i]));
      } else {
        where.append("RTRIM(").append(column.toUpperCase(java.util.Locale.ROOT)).append(") = ?");
        args.add(parts[i].replace("\\|", "|").replace("\\\\", "\\"));
      }
    }
    List<Map<String, Object>> rows =
        jdbc.query(
            select + where,
            com.acme.insurance.pas.batch.migration.SourceRows.INSTANCE,
            args.toArray());
    return rows.isEmpty() ? Map.of() : prepare(rows.get(0), table, isTarget);
  }

  private String sourceSelect(MigrationTable table) {
    return "SELECT "
        + String.join(", ", hashColumns(table)).toUpperCase(java.util.Locale.ROOT)
        + " FROM "
        + source.table(table.sourceTable());
  }

  /** Holders hash their PII too (plaintext in DB2, decrypted in memory for the target). */
  private static List<String> hashColumns(MigrationTable table) {
    return table.sourceColumns();
  }

  private static String targetSelect(MigrationTable table) {
    List<String> columns =
        table == MigrationTable.POLICY_HOLDERS
            ? com.acme.insurance.pas.batch.migration.TargetWriter.targetColumnsOf(table)
            : table.columns();
    return "SELECT " + String.join(", ", columns) + " FROM " + table.targetTable();
  }

  private Map<String, Object> prepare(
      Map<String, Object> row, MigrationTable table, boolean isTarget) {
    if (isTarget && table == MigrationTable.POLICY_HOLDERS) {
      Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
      pii.decryptInPlace(copy);
      return copy;
    }
    return row;
  }

  private TreeMap<String, String> hashMap(
      JdbcTemplate jdbc, String select, MigrationTable table, boolean isTarget) {
    TreeMap<String, String> hashes = new TreeMap<>();
    jdbc.query(
        select,
        rs -> {
          Map<String, Object> row =
              prepare(
                  com.acme.insurance.pas.batch.migration.SourceRows.INSTANCE.mapRow(rs, 0),
                  table,
                  isTarget);
          hashes.put(
              Canonical.key(table, row),
              Canonical.sha256(Canonical.record(hashColumns(table), row)));
        });
    return hashes;
  }

  private static String digest(TreeMap<String, String> hashes) {
    StringBuilder sb = new StringBuilder();
    hashes.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
    return Canonical.sha256(sb.toString());
  }
}
