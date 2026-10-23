package com.acme.insurance.pas.batch.migration.bulk;

import com.acme.insurance.pas.batch.migration.source.Db2Source;
import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;

public final class MigrationRowReader implements ItemStreamReader<MigrationRow> {
  private static final String LAST_KEY_PREFIX = "lastKey.";
  private final Db2Source source;
  private final MigrationTable table;
  private final String minimum;
  private final String maximum;
  private final int pageSize;
  private List<Map<String, Object>> page = List.of();
  private int pageIndex;
  private List<Object> lastKey;

  public MigrationRowReader(
      Db2Source source, MigrationTable table, String minimum, String maximum, int pageSize) {
    this.source = source;
    this.table = table;
    this.minimum = minimum;
    this.maximum = maximum;
    this.pageSize = pageSize;
  }

  @Override
  public void open(ExecutionContext executionContext) {
    List<Object> restored = new ArrayList<>();
    for (int index = 0; index < table.keyColumns().size(); index++) {
      String key = LAST_KEY_PREFIX + index;
      if (!executionContext.containsKey(key)) {
        return;
      }
      restored.add(parseKey(executionContext.getString(key), table.keyTypes().get(index)));
    }
    lastKey = restored;
  }

  @Override
  public MigrationRow read() {
    if (pageIndex >= page.size()) {
      page = source.readPage(table, lastKey, minimum, maximum, pageSize);
      pageIndex = 0;
      if (page.isEmpty()) {
        return null;
      }
    }
    Map<String, Object> row = page.get(pageIndex++);
    lastKey = table.keyColumns().stream().map(key -> row.get(key.toLowerCase())).toList();
    return new MigrationRow(table, row);
  }

  @Override
  public void update(ExecutionContext executionContext) {
    if (lastKey == null) {
      return;
    }
    for (int index = 0; index < lastKey.size(); index++) {
      executionContext.putString(LAST_KEY_PREFIX + index, String.valueOf(lastKey.get(index)));
    }
  }

  private static Object parseKey(String value, int sqlType) {
    return switch (sqlType) {
      case Types.SMALLINT -> Short.valueOf(value);
      case Types.INTEGER -> Integer.valueOf(value);
      case Types.DATE -> LocalDate.parse(value);
      default -> value;
    };
  }
}
