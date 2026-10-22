package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PremiumPartitionerTest {
  @Test
  void createsPolicyRangesInDatabaseOrder() {
    NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(
            List.of(
                new String[] {"POL000000101", "POL000000205"},
                new String[] {"POL000000301", "POL000000408"}));

    Map<String, ExecutionContext> partitions = new PremiumPartitioner(jdbcTemplate).partition(8);

    assertThat(partitions.keySet()).containsExactly("premiumPartition0", "premiumPartition1");
    assertThat(partitions.get("premiumPartition0").getString("minimumPolicy"))
        .isEqualTo("POL000000101");
    assertThat(partitions.get("premiumPartition0").getString("maximumPolicy"))
        .isEqualTo("POL000000205");
    assertThat(partitions.get("premiumPartition1").getString("minimumPolicy"))
        .isEqualTo("POL000000301");
    assertThat(partitions.get("premiumPartition1").getString("maximumPolicy"))
        .isEqualTo("POL000000408");
    assertThat(partitions.values()).allMatch(context -> context.get("empty").equals(false));
  }

  @Test
  void createsOneEmptyPartitionForAnEmptyBook() {
    NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    Map<String, ExecutionContext> partitions = new PremiumPartitioner(jdbcTemplate).partition(8);

    assertThat(partitions).containsOnlyKeys("premiumPartition0");
    assertThat(partitions.get("premiumPartition0").get("empty")).isEqualTo(true);
  }
}
