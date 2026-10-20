package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RatingTables} over in-memory rows with the same effective-dating rules as {@link
 * JdbcRatingTables}. For unit and parity tests; not thread-safe while being populated.
 */
public final class InMemoryRatingTables implements RatingTables {

  private record Row<T>(String key, LocalDate effective, T value) {}

  private final List<String> factorSets = new ArrayList<>();
  private final List<Row<BigDecimal>> baseRates = new ArrayList<>();
  private final List<Row<RatingParameters>> parameters = new ArrayList<>();
  private final List<Row<BigDecimal>> territoryFactors = new ArrayList<>();
  private final List<Row<BigDecimal>> classFactors = new ArrayList<>();

  public InMemoryRatingTables factorSet(String factorSet) {
    factorSets.add(factorSet);
    return this;
  }

  public InMemoryRatingTables baseRate(String policyType, LocalDate effective, BigDecimal rate) {
    baseRates.add(new Row<>(policyType, effective, rate));
    return this;
  }

  public InMemoryRatingTables parameters(LocalDate effective, RatingParameters p) {
    parameters.add(new Row<>("", effective, p));
    return this;
  }

  public InMemoryRatingTables territoryFactor(
      String factorSet, String code, LocalDate effective, BigDecimal factor) {
    territoryFactors.add(new Row<>(factorSet + "|" + code, effective, factor));
    return this;
  }

  public InMemoryRatingTables classFactor(
      String factorSet, String code, LocalDate effective, BigDecimal factor) {
    classFactors.add(new Row<>(factorSet + "|" + code, effective, factor));
    return this;
  }

  @Override
  public RatingSnapshot load(LocalDate asOf, String factorSet) {
    if (!factorSets.contains(factorSet)) {
      throw new RatingDataException("unknown rating factor set " + factorSet);
    }
    Row<RatingParameters> p =
        latest(parameters, asOf, "")
            .orElseThrow(
                () -> new RatingDataException("no rating parameters effective on " + asOf));
    LocalDate[] newest = {null};
    Map<String, BigDecimal> base = latestByKey(newest, baseRates, asOf, "");
    Map<String, BigDecimal> territory =
        latestByKey(newest, territoryFactors, asOf, factorSet + "|");
    Map<String, BigDecimal> classes = latestByKey(newest, classFactors, asOf, factorSet + "|");
    RatingSnapshot.requireVersioned(newest[0], p.effective(), p.value().rateVersion(), asOf);
    return new RatingSnapshot(asOf, factorSet, base, territory, classes, p.value());
  }

  private static <T> Optional<Row<T>> latest(List<Row<T>> rows, LocalDate asOf, String key) {
    return rows.stream()
        .filter(r -> r.key().equals(key) && !r.effective().isAfter(asOf))
        .max(Comparator.comparing(Row::effective));
  }

  private static Map<String, BigDecimal> latestByKey(
      LocalDate[] newest, List<Row<BigDecimal>> rows, LocalDate asOf, String prefix) {
    Map<String, Row<BigDecimal>> best = new HashMap<>();
    for (Row<BigDecimal> r : rows) {
      if (!r.key().startsWith(prefix) || r.effective().isAfter(asOf)) {
        continue;
      }
      best.merge(
          r.key().substring(prefix.length()),
          r,
          (a, b) -> a.effective().isAfter(b.effective()) ? a : b);
    }
    Map<String, BigDecimal> out = new HashMap<>();
    best.forEach(
        (k, r) -> {
          out.put(k, r.value());
          if (newest[0] == null || r.effective().isAfter(newest[0])) {
            newest[0] = r.effective();
          }
        });
    return out;
  }
}
