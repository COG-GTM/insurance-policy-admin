package com.acme.insurance.pas.batch.migration.bulk;

import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;

public final class BulkLoadPartitioner implements Partitioner {
  private final Db2Source source;
  private final int gridSize;
  private final MigrationTable table;

  public BulkLoadPartitioner(Db2Source source, int gridSize, MigrationTable table) {
    this.source = source;
    this.gridSize = gridSize;
    this.table = table;
  }

  @Override
  public Map<String, ExecutionContext> partition(int gridSize) {
    Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
    var ranges = source.keyRanges(table, Math.min(this.gridSize, Math.max(1, gridSize)));
    if (ranges.isEmpty()) {
      ExecutionContext context = new ExecutionContext();
      context.putString("table", table.name());
      context.putString("partition", "empty");
      partitions.put(table.name() + "-empty", context);
      return partitions;
    }
    for (int index = 0; index < ranges.size(); index++) {
      Db2Source.KeyRange range = ranges.get(index);
      ExecutionContext context = new ExecutionContext();
      context.putString("table", table.name());
      context.putString("partition", Integer.toString(index));
      context.putString("minimum", range.minimum());
      context.putString("maximum", range.maximum());
      partitions.put(table.name() + "-" + index, context);
    }
    return partitions;
  }
}
