package com.acme.insurance.pas.policyservice.config;

import com.acme.insurance.pas.persistence.DatabaseVendor;
import com.acme.insurance.pas.persistence.DefaultCoverageJdbcRepository;
import com.acme.insurance.pas.persistence.EventOutboxJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyAuditJdbcRepository;
import com.acme.insurance.pas.persistence.EndorsementJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyChangeAuditJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyHolderJdbcRepository;
import com.acme.insurance.pas.persistence.PolicyJdbcRepository;
import com.acme.insurance.pas.persistence.PolicySequence;
import com.acme.insurance.pas.persistence.UnderwritingDecisionJdbcRepository;
import java.sql.DatabaseMetaData;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/** Wires the pas-persistence repositories the policy service reads and writes through. */
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

  @Bean
  UnderwritingDecisionJdbcRepository underwritingDecisionJdbcRepository(JdbcClient jdbc) {
    return new UnderwritingDecisionJdbcRepository(jdbc);
  }

  @Bean
  DefaultCoverageJdbcRepository defaultCoverageJdbcRepository(JdbcClient jdbc) {
    return new DefaultCoverageJdbcRepository(jdbc);
  }

  @Bean
  EventOutboxJdbcRepository eventOutboxJdbcRepository(JdbcClient jdbc) {
    return new EventOutboxJdbcRepository(jdbc);
  }

  @Bean
  PolicyAuditJdbcRepository policyAuditJdbcRepository(JdbcClient jdbc) {
    return new PolicyAuditJdbcRepository(jdbc);
  }

  @Bean
  PolicySequence policySequence(JdbcClient jdbc, DataSource dataSource)
      throws MetaDataAccessException {
    String product =
        JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
    return new PolicySequence(jdbc, DatabaseVendor.fromProductName(product));
  }

  @Bean
  BusinessClock businessClock(@Value("${pas.business-zone:America/New_York}") ZoneId zone) {
    return new BusinessClock(Clock.systemUTC(), zone);
  }

  @Bean
  EndorsementJdbcRepository endorsementJdbcRepository(JdbcClient jdbc) {
    return new EndorsementJdbcRepository(jdbc);
  }

  @Bean
  PolicyChangeAuditJdbcRepository policyChangeAuditJdbcRepository(JdbcClient jdbc) {
    return new PolicyChangeAuditJdbcRepository(jdbc);
  }
}
