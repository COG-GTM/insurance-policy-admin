package com.acme.insurance.pas.policyservice.config;

import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the pas-persistence repositories the policy service reads through. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

  @Bean
  PolicyJdbcRepository policyJdbcRepository(JdbcClient jdbc) {
    return new PolicyJdbcRepository(jdbc);
  }

  @Bean
  PolicyHolderJdbcRepository policyHolderJdbcRepository(JdbcClient jdbc) {
    return new PolicyHolderJdbcRepository(jdbc);
  }
}
