package com.acme.insurance.pas.batch.migration.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.parity.GoldenComparator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParallelRunComparatorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void rowComparisonMatchesGoldenComparatorForNumbersAndPaddedStrings() throws Exception {
    Map<String, Object> legacy =
        Map.of("total_premium", new BigDecimal("1.00"), "installment_code", "AN  ");
    Map<String, Object> same =
        Map.of("total_premium", new BigDecimal("1.0"), "installment_code", "AN");
    Map<String, Object> different =
        Map.of("total_premium", new BigDecimal("1.01"), "installment_code", "AN");

    assertThat(rowDifferences(legacy, same)).isEmpty();
    assertThat(goldenDifferences(legacy, same)).isEmpty();
    assertThat(rowDifferences(legacy, different)).containsExactly("outputs.total_premium");
    assertThat(goldenDifferences(legacy, different)).containsExactly("outputs.total_premium");
  }

  private List<String> rowDifferences(Map<String, Object> expected, Map<String, Object> actual) {
    return expected.entrySet().stream()
        .filter(
            entry -> !ParallelRunService.equivalent(entry.getValue(), actual.get(entry.getKey())))
        .map(entry -> "outputs." + entry.getKey())
        .toList();
  }

  private List<String> goldenDifferences(Map<String, Object> expected, Map<String, Object> actual)
      throws Exception {
    return GoldenComparator.compare(mapper.valueToTree(expected), mapper.valueToTree(actual))
        .stream()
        .map(GoldenComparator.Difference::path)
        .toList();
  }
}
