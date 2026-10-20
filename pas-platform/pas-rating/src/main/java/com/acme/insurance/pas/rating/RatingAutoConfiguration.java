package com.acme.insurance.pas.rating;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Wires the rating component into pas-policy-service and pas-batch. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(RatingProperties.class)
public class RatingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public PremiumCalculator premiumCalculator() {
    return new PremiumCalculator();
  }

  @Bean
  @ConditionalOnMissingBean(RatingTables.class)
  @ConditionalOnBean(DataSource.class)
  public JdbcRatingTables jdbcRatingTables(DataSource dataSource) {
    return new JdbcRatingTables(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(RatingTables.class)
  public PremiumRatingService premiumRatingService(
      RatingTables tables, PremiumCalculator calculator, RatingProperties properties) {
    return new PremiumRatingService(tables, calculator, properties);
  }
}
