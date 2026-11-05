package com.acme.insurance.pas.batch.rating;

import com.acme.insurance.pas.persistence.RatingFactorJdbcRepository;
import com.acme.insurance.pas.persistence.RatingRateJdbcRepository;
import com.acme.insurance.pas.rating.PremiumRater;
import com.acme.insurance.pas.rating.RatingFactorSource;
import com.acme.insurance.pas.rating.RatingReferenceData;
import com.acme.insurance.pas.rating.ReferenceRatingTables;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the shared pas-rating component (AB-522) over the rate and factor reference tables. The
 * factor source is the feature flag {@code pas.rating.factor-source} (default {@code
 * legacy-neutral}, PREMBAT parity); see docs/pas/rating.md.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RatingProperties.class)
public class RatingConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(RatingConfiguration.class);

  @Bean
  RatingRateJdbcRepository ratingRateJdbcRepository(JdbcClient jdbc) {
    return new RatingRateJdbcRepository(jdbc);
  }

  @Bean
  RatingFactorJdbcRepository ratingFactorJdbcRepository(JdbcClient jdbc) {
    return new RatingFactorJdbcRepository(jdbc);
  }

  @Bean
  ReferenceRatingTables referenceRatingTables(
      RatingRateJdbcRepository rates, RatingProperties properties) {
    return new ReferenceRatingTables(
        RatingReferenceData.of(rates::baseRatesInForce, rates::parametersInForce),
        properties.cacheTtl(),
        Clock.systemUTC());
  }

  @Bean
  RatingFactorSource ratingFactorSource(
      RatingProperties properties, RatingFactorJdbcRepository factors) {
    LOG.info("pas.rating.factor-source={}", properties.factorSource());
    return properties.factorSource().source(factors::territoryFactor, factors::classFactor);
  }

  @Bean
  PremiumRater premiumRater(ReferenceRatingTables tables, RatingFactorSource factorSource) {
    return new PremiumRater(tables, factorSource);
  }
}
