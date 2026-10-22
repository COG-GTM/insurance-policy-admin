package com.acme.insurance.pas.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenComparatorTest {
  private final ObjectMapper mapper =
      new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

  @Test
  void decimalScaleDoesNotCreateADifference() throws Exception {
    assertThat(compare("{\"amount\":1.0}", "{\"amount\":1.00}")).isEmpty();
  }

  @Test
  void centDifferenceFailsAtTheFieldPath() throws Exception {
    assertThat(compare("{\"amount\":1.00}", "{\"amount\":1.01}"))
        .extracting(GoldenComparator.Difference::path)
        .containsExactly("outputs.amount");
  }

  @Test
  void missingObjectKeyIsReported() throws Exception {
    assertThat(compare("{\"status\":\"AP\"}", "{}"))
        .extracting(GoldenComparator.Difference::path)
        .containsExactly("outputs.status");
  }

  @Test
  void arrayLengthMismatchIsReportedByIndex() throws Exception {
    assertThat(compare("{\"items\":[1]}", "{\"items\":[1,2]}"))
        .extracting(GoldenComparator.Difference::path)
        .containsExactly("outputs.items[1]");
  }

  @Test
  void outputDifferencesUseTheOutputsRootPath() throws Exception {
    assertThat(
            compare("{\"decision\":[{\"riskScore\":299}]}", "{\"decision\":[{\"riskScore\":499}]}"))
        .extracting(GoldenComparator.Difference::path)
        .containsExactly("outputs.decision[0].riskScore");
  }

  @Test
  void trailingSpacesAreIgnoredForStrings() throws Exception {
    assertThat(compare("{\"status\":\"AP   \"}", "{\"status\":\"AP\"}")).isEmpty();
  }

  @Test
  void knownDifferenceCoversOnlyItsFieldAndDescendants() {
    assertThat(KnownDifferences.covers("outputs.decision", "outputs.decision[0].riskScore"))
        .isTrue();
    assertThat(KnownDifferences.covers("outputs.decision", "outputs.policyUpdate")).isFalse();
  }

  private List<GoldenComparator.Difference> compare(String expected, String actual)
      throws Exception {
    return GoldenComparator.compare(mapper.readTree(expected), mapper.readTree(actual));
  }
}
