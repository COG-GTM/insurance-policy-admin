package com.acme.insurance.pas.batch.migration;

import com.acme.insurance.pas.batch.migration.ReconciliationReport.Check;
import com.acme.insurance.pas.batch.migration.ReconciliationReport.Difference;
import com.acme.insurance.pas.batch.migration.ReconciliationReport.DifferenceKind;
import com.acme.insurance.pas.batch.migration.ReconciliationReport.Kind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Compares ACMEINS on DB2 with ACMEINS on PostgreSQL (AB-529): row counts of all seven tables,
 * premium and limit totals by policy type and branch, the policyholder orphan count (Q-37) and a
 * record-level SHA-256 comparison of POLICIES, COVERAGES and PREMIUMS. All SQL is portable and runs
 * unchanged on both databases. Group values are compared after trimming CHAR padding.
 */
public class Reconciler {

  /** Stored record differences per table; the count in the report is always complete. */
  static final int MAX_STORED_DIFFERENCES = 1000;

  static final String POLICY_TOTALS =
      """
      SELECT POLICY_TYPE, BRANCH_CODE, COUNT(*) AS ROWS_, SUM(TOTAL_PREMIUM) AS TOTAL_PREMIUM,
             SUM(COVERAGE_LIMIT) AS COVERAGE_LIMIT, SUM(DEDUCTIBLE) AS DEDUCTIBLE
        FROM ACMEINS.POLICIES
       GROUP BY POLICY_TYPE, BRANCH_CODE
      """;

  static final String COVERAGE_TOTALS =
      """
      SELECT P.POLICY_TYPE, P.BRANCH_CODE, COUNT(*) AS ROWS_, SUM(C.PREMIUM) AS PREMIUM,
             SUM(C.COVERAGE_LIMIT) AS COVERAGE_LIMIT, SUM(C.DEDUCTIBLE) AS DEDUCTIBLE
        FROM ACMEINS.COVERAGES C
        LEFT JOIN ACMEINS.POLICIES P ON P.POLICY_NUMBER = C.POLICY_NUMBER
       GROUP BY P.POLICY_TYPE, P.BRANCH_CODE
      """;

  static final String PREMIUM_TOTALS =
      """
      SELECT P.POLICY_TYPE, P.BRANCH_CODE, COUNT(*) AS ROWS_, SUM(R.TOTAL_PREMIUM) AS TOTAL_PREMIUM,
             SUM(R.TAX_AMT) AS TAX_AMT, SUM(R.INSTALLMENT_AMT) AS INSTALLMENT_AMT
        FROM ACMEINS.PREMIUMS R
        LEFT JOIN ACMEINS.POLICIES P ON P.POLICY_NUMBER = R.POLICY_NUMBER
       GROUP BY P.POLICY_TYPE, P.BRANCH_CODE
      """;

  static final String HOLDER_ORPHANS =
      """
      SELECT COUNT(*) FROM ACMEINS.POLICIES P
       WHERE NOT EXISTS (SELECT 1 FROM ACMEINS.POLICY_HOLDERS H WHERE H.CUST_ID = P.POLICYHOLDER_ID)
      """;

  static final String DECISION_KEYS =
      """
      SELECT COUNT(*) FROM (SELECT DISTINCT POLICY_NUMBER, DECISION_DATE
                              FROM ACMEINS.UNDERWRITING_DECISIONS) D
      """;

  private final int digits;
  private final List<MigrationTable> hashTables;

  public Reconciler(int digits, List<MigrationTable> hashTables) {
    this.digits = digits;
    this.hashTables = List.copyOf(hashTables);
  }

  public ReconciliationReport reconcile(LocalDate businessDate, JdbcClient db2, JdbcClient pg) {
    List<Check> checks = new ArrayList<>();
    for (MigrationTable table : MigrationTable.values()) {
      checks.add(
          new Check(
              Kind.COUNT,
              table,
              "ALL",
              "ROWS",
              BigDecimal.valueOf(count(db2, table)),
              BigDecimal.valueOf(count(pg, table))));
    }
    checks.addAll(totals(MigrationTable.POLICIES, POLICY_TOTALS, db2, pg));
    checks.addAll(totals(MigrationTable.COVERAGES, COVERAGE_TOTALS, db2, pg));
    checks.addAll(totals(MigrationTable.PREMIUMS, PREMIUM_TOTALS, db2, pg));
    checks.add(
        new Check(
            Kind.ORPHAN,
            MigrationTable.POLICIES,
            "POLICYHOLDER_ID",
            "ORPHANS",
            BigDecimal.valueOf(db2.sql(HOLDER_ORPHANS).query(Long.class).single()),
            BigDecimal.valueOf(pg.sql(HOLDER_ORPHANS).query(Long.class).single())));
    List<Difference> stored = new ArrayList<>();
    long differenceCount = 0;
    for (MigrationTable table : hashTables) {
      HashResult result = compareHashes(table, db2, pg);
      differenceCount += result.count;
      stored.addAll(result.stored);
      checks.add(
          new Check(
              Kind.HASH,
              table,
              "ALL",
              "MATCHING_RECORDS",
              BigDecimal.valueOf(result.db2Rows),
              BigDecimal.valueOf(result.db2Rows - result.count)));
    }
    return new ReconciliationReport(businessDate, checks, stored, differenceCount);
  }

  private static long count(JdbcClient jdbc, MigrationTable table) {
    String sql =
        table == MigrationTable.UNDERWRITING_DECISIONS
            ? DECISION_KEYS
            : "SELECT COUNT(*) FROM " + table.qualifiedName();
    return jdbc.sql(sql).query(Long.class).single();
  }

  private static List<Check> totals(
      MigrationTable table, String sql, JdbcClient db2, JdbcClient pg) {
    Map<String, Map<String, BigDecimal>> left = groupTotals(db2, sql);
    Map<String, Map<String, BigDecimal>> right = groupTotals(pg, sql);
    Set<String> dimensions = new TreeSet<>(left.keySet());
    dimensions.addAll(right.keySet());
    List<Check> checks = new ArrayList<>();
    for (String dimension : dimensions) {
      Map<String, BigDecimal> l = left.getOrDefault(dimension, Map.of());
      Map<String, BigDecimal> r = right.getOrDefault(dimension, Map.of());
      Set<String> metrics = new TreeSet<>(l.keySet());
      metrics.addAll(r.keySet());
      for (String metric : metrics) {
        checks.add(new Check(Kind.TOTAL, table, dimension, metric, l.get(metric), r.get(metric)));
      }
    }
    return checks;
  }

  private static Map<String, Map<String, BigDecimal>> groupTotals(JdbcClient jdbc, String sql) {
    Map<String, Map<String, BigDecimal>> totals = new TreeMap<>();
    jdbc.sql(sql)
        .query(
            rs -> {
              String dimension = dimension(rs.getString(1)) + "/" + dimension(rs.getString(2));
              Map<String, BigDecimal> metrics = new LinkedHashMap<>();
              int columns = rs.getMetaData().getColumnCount();
              for (int i = 3; i <= columns; i++) {
                String name = rs.getMetaData().getColumnLabel(i).toUpperCase(java.util.Locale.ROOT);
                BigDecimal value = rs.getBigDecimal(i);
                metrics.put(
                    name.equals("ROWS_") ? "ROWS" : name, value == null ? BigDecimal.ZERO : value);
              }
              totals.put(dimension, metrics);
            });
    return totals;
  }

  private static String dimension(String value) {
    return value == null ? "(none)" : value.strip();
  }

  private record HashResult(long db2Rows, long count, List<Difference> stored) {}

  private HashResult compareHashes(MigrationTable table, JdbcClient db2, JdbcClient pg) {
    Set<String> buckets = new TreeSet<>(TableSnapshot.buckets(db2, table, digits));
    buckets.addAll(TableSnapshot.buckets(pg, table, digits));
    long db2Rows = 0;
    long count = 0;
    List<Difference> stored = new ArrayList<>();
    for (String bucket : buckets) {
      TableSnapshot left = TableSnapshot.read(db2, table, digits, bucket);
      TableSnapshot right = TableSnapshot.read(pg, table, digits, bucket);
      db2Rows += left.rows().size();
      List<Difference> found = new ArrayList<>();
      left.duplicateKeys()
          .forEach(k -> found.add(new Difference(table, k, DifferenceKind.DUPLICATE_KEY)));
      right
          .duplicateKeys()
          .forEach(k -> found.add(new Difference(table, k, DifferenceKind.DUPLICATE_KEY)));
      left.rows()
          .forEach(
              (key, row) -> {
                TableSnapshot.Row other = right.rows().get(key);
                if (other == null) {
                  found.add(new Difference(table, key, DifferenceKind.MISSING_IN_PG));
                } else if (!other.hash().equals(row.hash())
                    && !right.duplicateKeys().contains(key)) {
                  found.add(new Difference(table, key, DifferenceKind.HASH_MISMATCH));
                }
              });
      right.rows().keySet().stream()
          .filter(key -> !left.rows().containsKey(key))
          .forEach(key -> found.add(new Difference(table, key, DifferenceKind.MISSING_IN_DB2)));
      count += found.size();
      found.stream()
          .limit(Math.max(0, MAX_STORED_DIFFERENCES - stored.size()))
          .forEach(stored::add);
    }
    return new HashResult(db2Rows, count, stored);
  }
}
