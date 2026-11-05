package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.persistence.ExtractRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeedLayoutTest {

  @Test
  void layoutsKeepTheLegacyRecordLengths() {
    assertThat(FeedLayouts.CLAIMS_POLICY.recordLength()).isEqualTo(200);
    assertThat(FeedLayouts.CLAIMS_POLICY.usedLength()).isEqualTo(104);
    assertThat(FeedLayouts.CLAIMS_COVERAGE.recordLength()).isEqualTo(150);
    assertThat(FeedLayouts.CLAIMS_COVERAGE.usedLength()).isEqualTo(123);
    assertThat(FeedLayouts.ACTUARIAL_EXPOSURE.recordLength()).isEqualTo(300);
  }

  @Test
  void fieldsFollowTheJclSelectListInOrder() {
    assertThat(FeedLayouts.CLAIMS_POLICY.fields())
        .extracting(FeedLayout.Field::column)
        .containsExactly(
            "POLICY_NUMBER",
            "POLICY_TYPE",
            "POLICY_STATUS",
            "EFFECTIVE_DATE",
            "EXPIRY_DATE",
            "POLICYHOLDER_ID",
            "AGENT_CODE",
            "BRANCH_CODE",
            "TOTAL_PREMIUM",
            "DEDUCTIBLE",
            "COVERAGE_LIMIT",
            "UW_STATUS",
            "RISK_SCORE");
    assertThat(FeedLayouts.CLAIMS_COVERAGE.fields())
        .extracting(FeedLayout.Field::column)
        .containsExactly(
            "POLICY_NUMBER",
            "SEQUENCE_NUM",
            "COVERAGE_TYPE",
            "DESCRIPTION",
            "COVERAGE_LIMIT",
            "DEDUCTIBLE",
            "PREMIUM",
            "EFFECTIVE_DATE",
            "EXPIRY_DATE",
            "STATUS");
  }

  @Test
  void numbersAreSignedZeroPaddedWithAnExplicitPoint() {
    FeedLayout layout =
        layout(FeedLayout.Field.decimal("A", 5, 2), FeedLayout.Field.integer("B", 3));
    assertThat(layout.render(row("A", new BigDecimal("-1.5"), "B", 7))).isEqualTo("-001.50+007   ");
    assertThat(layout.render(row("A", BigDecimal.ZERO, "B", -12))).isEqualTo("+000.00-012   ");
  }

  @Test
  void nullsAreSpacesAndTextIsLeftAligned() {
    FeedLayout layout =
        layout(
            FeedLayout.Field.text("A", 4),
            FeedLayout.Field.date("D"),
            FeedLayout.Field.decimal("N", 3, 1));
    assertThat(layout.render(row("A", "X", "D", null, "N", null))).isEqualTo("X" + " ".repeat(21));
    assertThat(
            layout.render(
                row("A", null, "D", LocalDate.of(2026, 1, 2), "N", new BigDecimal("1.0"))))
        .isEqualTo("    2026-01-02+01.0   ");
  }

  @Test
  void valuesThatDoNotFitFailInsteadOfBeingTruncated() {
    FeedLayout layout = layout(FeedLayout.Field.text("A", 2), FeedLayout.Field.decimal("N", 3, 1));
    assertThatThrownBy(() -> layout.render(row("A", "ABC", "N", null)))
        .isInstanceOf(FeedLayout.FeedFormatException.class);
    assertThatThrownBy(() -> layout.render(row("A", "A", "N", new BigDecimal("100.0"))))
        .isInstanceOf(FeedLayout.FeedFormatException.class);
    assertThatThrownBy(() -> layout.render(row("A", "A", "N", new BigDecimal("1.25"))))
        .isInstanceOf(FeedLayout.FeedFormatException.class);
  }

  @Test
  void fieldsLongerThanTheLreclAreRejected() {
    assertThatThrownBy(
            () ->
                new FeedLayout(
                    "X", FeedLayout.Format.FIXED_WIDTH, 3, List.of(FeedLayout.Field.text("A", 4))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void csvQuotesTextWithSeparatorsAndLeavesNullsEmpty() {
    FeedLayout csv =
        new FeedLayout(
            "C",
            FeedLayout.Format.CSV,
            30,
            List.of(
                FeedLayout.Field.text("A", 10),
                FeedLayout.Field.decimal("N", 31, 2),
                FeedLayout.Field.text("B", 5)));
    assertThat(csv.header()).isEqualTo("A,N,B");
    assertThat(csv.render(row("A", "x,\"y\"", "N", new BigDecimal("12"), "B", null)))
        .isEqualTo("\"x,\"\"y\"\"\",12.00,");
    assertThatThrownBy(
            () -> csv.render(row("A", "a".repeat(10), "N", new BigDecimal("1E20"), "B", "b")))
        .isInstanceOf(FeedLayout.FeedFormatException.class);
  }

  private static FeedLayout layout(FeedLayout.Field... fields) {
    int width = 0;
    for (FeedLayout.Field f : fields) {
      width += f.width();
    }
    return new FeedLayout("T", FeedLayout.Format.FIXED_WIDTH, width + 3, List.of(fields));
  }

  private static ExtractRow row(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put((String) pairs[i], pairs[i + 1]);
    }
    return new ExtractRow(values);
  }
}
