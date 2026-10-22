package com.acme.insurance.pas.policy.dev;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "pas.dev.seed-facade-data", havingValue = "true")
public class FacadeSeedConfiguration {
  @Bean
  ApplicationRunner facadeSeedRunner(FacadeSeedLoader loader) {
    return args -> loader.load();
  }
}
