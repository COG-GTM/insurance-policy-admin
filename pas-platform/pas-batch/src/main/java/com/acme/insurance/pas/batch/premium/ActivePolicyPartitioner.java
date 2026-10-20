package com.acme.insurance.pas.batch.premium;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Splits the active book (PREMBAT POL_CURSOR: {@code POLICY_STATUS = 'AC'}) into contiguous,
 * disjoint policy-number ranges of roughly equal size. The ranges are stored in the partition
 * execution contexts, so a restart re-uses the original ranges.
 */
public class ActivePolicyPartitioner implements Partitioner {

  static final String MIN_POLICY = "minPolicyNumber";
  static final String MAX_POLICY = "maxPolicyNumber";
  static final String PARTITION_NAME = "partitionName";

  private static final String RANGES_SQL =
      """
      SELECT bucket, MIN(policy_number) AS min_pn, MAX(policy_number) AS max_pn
      FROM (SELECT policy_number, NTILE(?) OVER (ORDER BY policy_number) AS bucket
            FROM pas.policies
            WHERE policy_status = 'AC') active
      GROUP BY bucket
      ORDER BY bucket
      """;

  private final JdbcTemplate jdbc;

  public ActivePolicyPartitioner(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public Map<String, ExecutionContext> partition(int gridSize) {
    List<String[]> ranges =
        jdbc.query(
            RANGES_SQL,
            (rs, i) -> new String[] {rs.getString("min_pn"), rs.getString("max_pn")},
            gridSize);
    Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
    if (ranges.isEmpty()) {
      // empty book: one partition whose range matches nothing, so the run still reports zeros
      partitions.put(name(0), context(0, "1", "0"));
      return partitions;
    }
    for (int i = 0; i < ranges.size(); i++) {
      partitions.put(name(i), context(i, ranges.get(i)[0], ranges.get(i)[1]));
    }
    return partitions;
  }

  /** Zero-padded so partition names sort in policy-number order (report assembly relies on it). */
  static String name(int index) {
    return String.format("partition%04d", index);
  }

  private static ExecutionContext context(int index, String min, String max) {
    ExecutionContext ctx = new ExecutionContext();
    ctx.putString(PARTITION_NAME, name(index));
    ctx.putString(MIN_POLICY, min);
    ctx.putString(MAX_POLICY, max);
    return ctx;
  }
}
