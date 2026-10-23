package com.acme.insurance.pas.rating.config;

import com.acme.insurance.pas.rating.PremiumCalculator;
import com.acme.insurance.pas.rating.RatingTableRepository;
import com.acme.insurance.pas.rating.jdbc.JdbcRatingTableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcClientAutoConfiguration.class})
@EnableConfigurationProperties(RatingProperties.class)
public class PasRatingAutoConfiguration {
  private static final Logger LOGGER = LoggerFactory.getLogger(PasRatingAutoConfiguration.class);

  @Bean
  @ConditionalOnBean(JdbcClient.class)
  @ConditionalOnMissingBean(RatingTableRepository.class)
  RatingTableRepository ratingTableRepository(JdbcClient jdbcClient) {
    return new JdbcRatingTableRepository(jdbcClient);
  }

  @Bean
  @ConditionalOnBean(RatingTableRepository.class)
  @ConditionalOnMissingBean
  PremiumCalculator premiumCalculator(
      RatingTableRepository repository, RatingProperties properties) {
    LOGGER.info("PAS rating factor mode: {}", properties.getFactorMode());
    return new PremiumCalculator(repository, properties.getFactorMode());
  }
}
