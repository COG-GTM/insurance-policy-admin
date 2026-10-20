package com.acme.insurance.pas.batch.extract;

import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Streaming JDBC access for extracts (PostgreSQL uses a server cursor inside the step tx). */
@Configuration
@EnableConfigurationProperties(ExtractProperties.class)
public class ExtractJdbcConfig {

  static final int FETCH_SIZE = 1000;

  @Bean
  public NamedParameterJdbcTemplate extractJdbcTemplate(DataSource dataSource) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.setFetchSize(FETCH_SIZE);
    return new NamedParameterJdbcTemplate(jdbc);
  }
}
