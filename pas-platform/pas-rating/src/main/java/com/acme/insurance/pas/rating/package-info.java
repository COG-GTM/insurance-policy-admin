/**
 * Shared premium rating component: a port of PREMBAT {@code 3200-CALCULATE-PREMIUM} (AB-522).
 *
 * <p>{@link com.acme.insurance.pas.rating.PremiumCalculator} holds the arithmetic only; every rate
 * comes from a {@link com.acme.insurance.pas.rating.RatingSnapshot} loaded through the {@link
 * com.acme.insurance.pas.rating.RatingTables} port ({@link
 * com.acme.insurance.pas.rating.JdbcRatingTables} in the apps, {@link
 * com.acme.insurance.pas.rating.InMemoryRatingTables} in tests).
 */
package com.acme.insurance.pas.rating;
