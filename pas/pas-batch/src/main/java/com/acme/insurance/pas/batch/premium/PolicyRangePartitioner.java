package com.acme.insurance.pas.batch.premium;

import com.acme.insurance.pas.persistence.PremiumRunJdbcRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

/**
 * Splits the active book into contiguous policy-number ranges of equal size. The ranges are stored
 * in the partition step executions, so a restart rates the unfinished ranges of the original split
 * even if policies were added since.
 */
public class PolicyRangePartitioner implements Partitioner {

  static final String FROM = "fromPolicy";
  static final String TO = "toPolicy";

  private final PremiumRunJdbcRepository repository;

  public PolicyRangePartitioner(PremiumRunJdbcRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, ExecutionContext> partition(int gridSize) {
    List<String> starts = repository.partitionStarts(gridSize);
    Map<String, ExecutionContext> partitions = new HashMap<>();
    if (starts.isEmpty()) {
      partitions.put("partition0", new ExecutionContext());
      return partitions;
    }
    for (int i = 0; i < starts.size(); i++) {
      ExecutionContext context = new ExecutionContext();
      context.putString(FROM, starts.get(i));
      if (i + 1 < starts.size()) {
        context.putString(TO, starts.get(i + 1));
      }
      partitions.put("partition" + i, context);
    }
    return partitions;
  }
}
