package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Before/after premium per policy for switching {@code pas.rating.factor-source} from {@code
 * legacy-neutral} to {@code table-driven}: the report Actuarial approves before the switch (FU-03,
 * quirk Q-03). Rates every request with both raters and lists the totals and the difference.
 */
public final class PremiumImpactReport {

  /** One policy: the legacy total, the table-driven total and both breakdowns for audit. */
  public record Line(String policyNumber, PremiumBreakdown legacy, PremiumBreakdown tableDriven) {

    public BigDecimal delta() {
      return tableDriven.totalPremium().subtract(legacy.totalPremium());
    }

    public boolean changed() {
      return delta().signum() != 0;
    }
  }

  private final List<Line> lines;

  private PremiumImpactReport(List<Line> lines) {
    this.lines = List.copyOf(lines);
  }

  /** {@code requests} maps policy number to request, in the order the report lists them. */
  public static PremiumImpactReport compare(
      Map<String, RatingRequest> requests, PremiumRater legacy, PremiumRater tableDriven) {
    Objects.requireNonNull(legacy, "legacy");
    Objects.requireNonNull(tableDriven, "tableDriven");
    List<Line> lines = new ArrayList<>();
    requests.forEach(
        (policyNumber, request) ->
            lines.add(new Line(policyNumber, legacy.rate(request), tableDriven.rate(request))));
    return new PremiumImpactReport(lines);
  }

  public List<Line> lines() {
    return lines;
  }

  public long changedCount() {
    return lines.stream().filter(Line::changed).count();
  }

  public BigDecimal totalDelta() {
    return lines.stream().map(Line::delta).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
  }

  /** CSV with a header row: policy number, legacy total, table-driven total, delta, factors. */
  public String toCsv() {
    StringBuilder csv =
        new StringBuilder(
            "policy_number,legacy_total,table_driven_total,delta,territory_factor,class_factor\n");
    for (Line line : lines) {
      csv.append(line.policyNumber().strip())
          .append(',')
          .append(line.legacy().totalPremium().toPlainString())
          .append(',')
          .append(line.tableDriven().totalPremium().toPlainString())
          .append(',')
          .append(line.delta().toPlainString())
          .append(',')
          .append(line.tableDriven().factors().territoryFactor().toPlainString())
          .append(',')
          .append(line.tableDriven().factors().classFactor().toPlainString())
          .append('\n');
    }
    return csv.toString();
  }
}
