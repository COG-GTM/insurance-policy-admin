package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Effective-dated factor lookup by code (territory or class). Empty means no row applies. Wired to
 * RatingFactorJdbcRepository by the applications; pas-rating depends on pas-domain only.
 */
@FunctionalInterface
public interface FactorLookup {

  Optional<BigDecimal> factor(String code, LocalDate ratingDate);

  /**
   * An immutable in-memory lookup over factor rows of all effective dates: the latest row on or
   * before the rating date. Codes compare like CHAR columns in SQL (trailing blanks ignored).
   */
  static <T> FactorLookup of(
      Collection<T> rows,
      Function<T, String> code,
      Function<T, LocalDate> effectiveDate,
      Function<T, BigDecimal> factor) {
    Map<String, NavigableMap<LocalDate, BigDecimal>> byCode = new HashMap<>();
    for (T row : rows) {
      byCode
          .computeIfAbsent(code.apply(row).stripTrailing(), c -> new TreeMap<>())
          .put(effectiveDate.apply(row), factor.apply(row));
    }
    Map<String, NavigableMap<LocalDate, BigDecimal>> pinned = Map.copyOf(byCode);
    return (lookupCode, ratingDate) -> {
      Objects.requireNonNull(ratingDate, "ratingDate");
      NavigableMap<LocalDate, BigDecimal> history = pinned.get(lookupCode.stripTrailing());
      return Optional.ofNullable(history)
          .map(h -> h.floorEntry(ratingDate))
          .map(Map.Entry::getValue);
    };
  }
}
