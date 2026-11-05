package com.acme.insurance.pas.batch.premium;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.persistence.PremiumRunItem;
import com.acme.insurance.pas.persistence.PremiumRunTotals;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PremiumReportTest {

  static final LocalDate RUN_DATE = LocalDate.of(2025, 3, 1);

  @Test
  void packsThePremiumAsPicS9_9V99Comp3() {
    assertThat(PremiumReport.packed(new BigDecimal("904.75")))
        .containsExactly(0x00, 0x00, 0x00, 0x90, 0x47, 0x5C);
    assertThat(PremiumReport.packed(new BigDecimal("439.00")))
        .containsExactly(0x00, 0x00, 0x00, 0x43, 0x90, 0x0C);
    assertThat(PremiumReport.packed(new BigDecimal("-1.5")))
        .containsExactly(0x00, 0x00, 0x00, 0x00, 0x15, 0x0D);
    assertThat(PremiumReport.packed(new BigDecimal("123456789012.34")))
        .as("high-order digits are truncated like a COBOL MOVE")
        .containsExactly(0x45, 0x67, 0x89, 0x01, 0x23, 0x4C);
  }

  @Test
  void matchesTheCobolReportOfTheReportTotalsCase() throws Exception {
    List<PremiumRunItem> items =
        List.of(
            PremiumRunItem.updated("POL-00000036", new BigDecimal("904.75")),
            PremiumRunItem.updated("POL-00000037", new BigDecimal("439.00")),
            PremiumRunItem.updated("POL-00000038", new BigDecimal("3647.50")));
    List<String> lines = render(items, new PremiumRunTotals(3, 3, 0));

    assertThat(lines).allSatisfy(l -> assertThat(l).hasSize(PremiumReport.RECORD_LENGTH));
    assertThat(lines.stream().map(PremiumGolden::escapeAndTrim).toList())
        .containsExactlyElementsOf(PremiumGolden.report("report-totals"));
  }

  @Test
  void printsTruthfulTotalsAndZeroForAPolicyThatWasNotRated() throws Exception {
    List<String> lines =
        render(
            List.of(
                PremiumRunItem.updated("POL-00000031", new BigDecimal("904.75")),
                PremiumRunItem.error("POL-00000032", "RatingOverflowException", "too large")),
            new PremiumRunTotals(2, 1, 1));
    assertThat(lines.get(3)).startsWith("POL-00000032 PREMIUM: \0\0\0\0\0\f");
    assertThat(lines.subList(5, 8).stream().map(l -> l.replaceAll(" +$", "")))
        .containsExactly(
            "TOTAL POLICIES READ:    0000002",
            "TOTAL POLICIES UPDATED: 0000001",
            "TOTAL ERRORS:           0000001");
  }

  @Test
  void writesAtomicallyAndARerunReplacesTheReport(@TempDir Path dir) throws Exception {
    Path first = PremiumReport.write(dir, RUN_DATE, sink -> {}, new PremiumRunTotals(0, 0, 0));
    Path second =
        PremiumReport.write(
            dir,
            RUN_DATE,
            sink -> sink.accept(PremiumRunItem.updated("POL-00000036", new BigDecimal("1"))),
            new PremiumRunTotals(1, 1, 0));
    assertThat(second).isEqualTo(first);
    assertThat(first.getFileName()).hasToString("ACME.PAS.REPORTS.PREMIUM.D250301.txt");
    try (var files = Files.list(dir)) {
      assertThat(files).containsExactly(first);
    }
    assertThat(Files.size(second)).isEqualTo(7L * (PremiumReport.RECORD_LENGTH + 1));
  }

  private static List<String> render(List<PremiumRunItem> items, PremiumRunTotals totals)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PremiumReport.render(RUN_DATE, sink -> items.forEach(sink), totals, out);
    return out.toString(StandardCharsets.ISO_8859_1).lines().toList();
  }
}
