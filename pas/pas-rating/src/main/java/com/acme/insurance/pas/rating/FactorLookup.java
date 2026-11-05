package com.acme.insurance.pas.rating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Effective-dated factor lookup by code (territory or class). Empty means no row applies. Wired to
 * RatingFactorJdbcRepository by the applications; pas-rating depends on pas-domain only.
 */
@FunctionalInterface
public interface FactorLookup {

  Optional<BigDecimal> factor(String code, LocalDate ratingDate);
}
