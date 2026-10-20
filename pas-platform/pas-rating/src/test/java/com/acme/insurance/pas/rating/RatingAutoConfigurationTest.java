package com.acme.insurance.pas.rating;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

class RatingAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RatingAutoConfiguration.class));

  @Test
  void defaultsToParityFactorSet() {
    runner
        .withBean(RatingTables.class, RatingFixtures::parity)
        .run(
            ctx -> {
              assertThat(ctx.getBean(RatingProperties.class).factorSet()).isEqualTo("PARITY");
              PremiumRatingService svc = ctx.getBean(PremiumRatingService.class);
              assertThat(svc.factorSet()).isEqualTo("PARITY");
              assertThat(
                      svc.rate(
                              RatingRequest.of(
                                  "AUT", RatingFixtures.RUN_DATE, RatingFixtures.RUN_DATE),
                              RatingFixtures.RUN_DATE)
                          .totalPremium())
                  .isEqualByComparingTo("904.75");
            });
  }

  @Test
  void filedIsSelectedByPropertyAndFailsFastWhileEmpty() {
    runner
        .withBean(RatingTables.class, RatingFixtures::parity)
        .withPropertyValues("pas.rating.factor-set=FILED")
        .run(
            ctx -> {
              PremiumRatingService svc = ctx.getBean(PremiumRatingService.class);
              assertThat(svc.factorSet()).isEqualTo("FILED");
              org.assertj.core.api.Assertions.assertThatThrownBy(
                      () -> svc.snapshot(RatingFixtures.RUN_DATE))
                  .isInstanceOf(RatingDataException.class)
                  .hasMessageContaining("FILED");
            });
  }

  @Test
  void jdbcAdapterIsTheDefaultWhenADataSourceExists() {
    runner
        .withBean(DataSource.class, SimpleDriverDataSource::new)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(JdbcRatingTables.class);
              assertThat(ctx).hasSingleBean(PremiumRatingService.class);
            });
  }

  @Test
  void noTablesNoService() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasSingleBean(PremiumCalculator.class);
          assertThat(ctx).doesNotHaveBean(PremiumRatingService.class);
        });
  }
}
