package com.acme.insurance.pas.batch.migration.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationRowTest {
  @Test
  void retainsNullableSourceColumnsWithoutExposingMutableInput() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("email", null);
    values.put("policy_number", "POL000000001");

    MigrationRow row = new MigrationRow(MigrationTable.POLICIES, values);
    values.put("policy_number", "POL000000002");

    assertThat(row.values())
        .containsEntry("email", null)
        .containsEntry("policy_number", "POL000000001");
    assertThatThrownBy(() -> row.values().put("policy_number", "POL000000003"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
