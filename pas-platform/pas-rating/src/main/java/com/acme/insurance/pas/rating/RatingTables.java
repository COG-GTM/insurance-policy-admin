package com.acme.insurance.pas.rating;

import java.time.LocalDate;

/**
 * Port to the rating reference tables ({@code pas.base_rates}, {@code pas.rating_parameters},
 * {@code pas.territory_factors}, {@code pas.class_factors}).
 *
 * <p>Like PREMBAT {@code 2000-LOAD-RATING-TABLES}, rows are selected with {@code EFFECTIVE_DATE <=
 * asOf} and loaded once into an immutable snapshot that is then used for every policy in the run.
 */
public interface RatingTables {

  /**
   * Loads the latest row of every code effective on {@code asOf}.
   *
   * @param factorSet territory/class factor set ({@code PARITY} or {@code FILED}, see {@code
   *     pas.rating.factor-set})
   * @throws RatingDataException if the factor set is unknown or has no territory/class factors, or
   *     if no base rates / rating parameters are effective on {@code asOf}
   */
  RatingSnapshot load(LocalDate asOf, String factorSet);
}
