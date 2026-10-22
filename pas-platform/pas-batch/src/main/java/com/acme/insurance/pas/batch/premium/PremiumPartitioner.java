package com.acme.insurance.pas.batch.premium;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class PremiumPartitioner implements Partitioner {
  private static final String RANGE_SQL =
      "SELECT bucket, MIN(policy_number) AS minimum_policy, "
          + "MAX(policy_number) AS maximum_policy, COUNT(*) AS policy_count "
          + "FROM (SELECT policy_number, ntile(:grid) OVER (ORDER BY policy_number) AS bucket "
          + "FROM pas.policies WHERE policy_status = 'AC') t "
          + "GROUP BY bucket ORDER BY bucket";

  private final NamedParameterJdbcTemplate jdbcTemplate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The Spring-managed JDBC template is retained for range queries.")
  public PremiumPartitioner(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Map<String, ExecutionContext> partition(int gridSize) {
    int grid = Math.max(gridSize, 1);
    var ranges =
        jdbcTemplate.query(
            RANGE_SQL,
            new MapSqlParameterSource("grid", grid),
            (resultSet, rowNumber) ->
                new String[] {
                  resultSet.getString("minimum_policy"), resultSet.getString("maximum_policy")
                });
    Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
    if (ranges.isEmpty()) {
      ExecutionContext context = new ExecutionContext();
      context.put("empty", true);
      partitions.put("premiumPartition0", context);
      return partitions;
    }
    for (int index = 0; index < ranges.size(); index++) {
      ExecutionContext context = new ExecutionContext();
      context.putString("minimumPolicy", ranges.get(index)[0]);
      context.putString("maximumPolicy", ranges.get(index)[1]);
      context.put("empty", false);
      partitions.put("premiumPartition" + index, context);
    }
    return partitions;
  }
}
