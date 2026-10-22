package com.acme.insurance.pas.policy.dev;

import com.acme.insurance.pas.domain.Coded;
import com.acme.insurance.pas.domain.CustomerType;
import com.acme.insurance.pas.domain.PolicyHolder;
import com.acme.insurance.pas.domain.RiskTier;
import com.acme.insurance.pas.domain.YesNo;
import com.acme.insurance.pas.policy.repository.PolicyHolderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

@Component
public class FacadeSeedLoader {
  private final DataSource dataSource;
  private final PolicyHolderRepository holders;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed collaborators are retained for seeding operations.")
  public FacadeSeedLoader(
      DataSource dataSource, PolicyHolderRepository holders, ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.holders = holders;
    this.objectMapper = objectMapper;
  }

  public void load() {
    new ResourceDatabasePopulator(new ClassPathResource("dev/facade-seed.sql")).execute(dataSource);
    try (InputStream inputStream =
        new ClassPathResource("dev/facade-seed-holders.json").getInputStream()) {
      List<FacadeHolderSeed> seeds = objectMapper.readValue(inputStream, new TypeReference<>() {});
      for (FacadeHolderSeed seed : seeds) {
        if (holders.findByCustId(seed.custId()).isEmpty()) {
          holders.insert(seed.toPolicyHolder());
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load facade holder seed data", exception);
    }
  }

  private record FacadeHolderSeed(
      String custId,
      String custType,
      String lastName,
      String firstName,
      String middleInit,
      String companyName,
      String addrLine1,
      String addrLine2,
      String city,
      String stateCode,
      String zipCode,
      String countryCode,
      String phone,
      String email,
      LocalDate dateOfBirth,
      String ssnLast4,
      String taxId,
      Integer creditScore,
      String riskTier) {
    private PolicyHolder toPolicyHolder() {
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      return new PolicyHolder(
          custId,
          Coded.of(CustomerType.class, custType),
          lastName,
          firstName,
          middleInit,
          companyName,
          addrLine1,
          addrLine2,
          city,
          stateCode,
          zipCode,
          countryCode == null ? "USA" : countryCode,
          phone,
          email,
          dateOfBirth,
          ssnLast4,
          taxId,
          creditScore,
          riskTier == null ? null : Coded.of(RiskTier.class, riskTier),
          Coded.of(YesNo.class, "N"),
          LocalDate.now(ZoneOffset.UTC),
          now);
    }
  }
}
