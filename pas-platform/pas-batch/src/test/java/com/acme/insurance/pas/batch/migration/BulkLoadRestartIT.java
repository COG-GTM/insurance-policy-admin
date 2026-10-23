package com.acme.insurance.pas.batch.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.insurance.pas.batch.migration.source.MigrationTable;
import com.acme.insurance.pas.batch.testsupport.PasTestDatabase;
import com.acme.insurance.pas.pii.PiiCipher;
import com.acme.insurance.pas.pii.PiiContext;
import com.acme.insurance.pas.pii.PolicyHolderPii;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "spring.batch.job.enabled=false",
      "pas.migration.enabled=true",
      "pas.migration.bulk.grid-size=1",
      "pas.migration.bulk.chunk-size=1",
      "pas.migration.source.schema=acmeins",
      "pas.migration.source.ssl=false",
      "pas.pii.encryption.active-key-id=migration-it",
      "pas.pii.encryption.active-key-version=1",
      "pas.pii.encryption.active-key=" + "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
      "pas.pii.blind-index.key=" + "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
    })
@ActiveProfiles("it")
class BulkLoadRestartIT {
  @Container
  static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("postgres:16-alpine");

  private static JdbcTemplate sourceJdbc;

  @Autowired private JdbcTemplate targetJdbc;
  @Autowired private PiiCipher cipher;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private JobLauncher jobLauncher;
  @Autowired private JobExplorer jobExplorer;

  @Autowired
  @Qualifier("bulkLoadJob")
  private Job bulkLoadJob;

  @DynamicPropertySource
  static void migrationProperties(DynamicPropertyRegistry registry) throws IOException {
    PasTestDatabase.migratePas(TARGET);
    SOURCE.start();
    new ResourceDatabasePopulator(new ClassPathResource("migration/acmeins-ddl.sql"))
        .execute(
            new DriverManagerDataSource(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword()));
    sourceJdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword()));
    registry.add("spring.datasource.url", TARGET::getJdbcUrl);
    registry.add("spring.datasource.username", TARGET::getUsername);
    registry.add("spring.datasource.password", TARGET::getPassword);
    registry.add("pas.migration.source.url", SOURCE::getJdbcUrl);
    registry.add("pas.migration.source.username", SOURCE::getUsername);
    registry.add("pas.migration.source.password", SOURCE::getPassword);
  }

  @BeforeEach
  void seedSource() {
    sourceJdbc.execute(
        "TRUNCATE acmeins.territory_factors, acmeins.underwriting_decisions, "
            + "acmeins.endorsements, acmeins.premiums, acmeins.coverages, acmeins.policies, "
            + "acmeins.policy_holders CASCADE");
    targetJdbc.execute(
        "TRUNCATE pas.underwriting_decisions, pas.endorsements, pas.premiums, pas.coverages, "
            + "pas.policies, pas.policy_holders, pas.territory_factors CASCADE");
    AcmeinsBookGenerator.populate(sourceJdbc, 2);
  }

  @AfterEach
  void removeInjectedFailure() {
    targetJdbc.execute("DROP TRIGGER IF EXISTS bulk_load_restart_failure ON pas.policy_holders");
    targetJdbc.execute("DROP FUNCTION IF EXISTS pas.reject_bulk_load_restart()");
  }

  @Test
  void resumesFailedPartitionAndRepeatedLoadLeavesPoliciesAndPiiSafe() throws Exception {
    targetJdbc.execute(
        """
        CREATE FUNCTION pas.reject_bulk_load_restart() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.cust_id = 'C000000002' THEN
            RAISE EXCEPTION USING ERRCODE = '57014';
          END IF;
          RETURN NEW;
        END $$
        """);
    targetJdbc.execute(
        "CREATE TRIGGER bulk_load_restart_failure BEFORE INSERT OR UPDATE ON pas.policy_holders "
            + "FOR EACH ROW EXECUTE FUNCTION pas.reject_bulk_load_restart()");
    JobParameters parameters =
        new JobParametersBuilder().addLong("run.id", 52901L, true).toJobParameters();

    JobExecution failed = jobLauncher.run(bulkLoadJob, parameters);

    assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(
            targetJdbc.queryForObject(
                "SELECT count(*) FROM pas.policy_holders WHERE cust_id = 'C000000001'",
                Integer.class))
        .isEqualTo(1);

    targetJdbc.execute("DROP TRIGGER bulk_load_restart_failure ON pas.policy_holders");
    JobExecution restarted = jobLauncher.run(bulkLoadJob, parameters);

    assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(restarted.getJobInstance().getInstanceId())
        .isEqualTo(failed.getJobInstance().getInstanceId());
    assertThat(jobExplorer.getJobInstance(restarted.getJobInstance().getInstanceId())).isNotNull();
    assertThat(
            restarted.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .filter(name -> name.startsWith("bulkLoadPartitionStep-"))
                .toList())
        .containsExactly(
            "bulkLoadPartitionStep-POLICY_HOLDERS",
            "bulkLoadPartitionStep-POLICIES",
            "bulkLoadPartitionStep-COVERAGES",
            "bulkLoadPartitionStep-PREMIUMS",
            "bulkLoadPartitionStep-ENDORSEMENTS",
            "bulkLoadPartitionStep-UNDERWRITING_DECISIONS",
            "bulkLoadPartitionStep-TERRITORY_FACTORS");
    assertAllRowsLoaded();
    assertThat(targetJdbc.queryForObject("SELECT count(*) FROM pas.change_log", Integer.class))
        .isZero();
    assertThat(
            targetJdbc.queryForObject(
                "SELECT count(*) FROM pas.policy_holders WHERE tax_id_enc IS NOT NULL "
                    + "AND date_of_birth_enc IS NOT NULL AND tax_id_bidx IS NOT NULL",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            targetJdbc.queryForObject(
                "SELECT count(*) FROM pas.policy_holders "
                    + "WHERE to_jsonb(policy_holders)::text LIKE '%TAX0000001%' "
                    + "OR to_jsonb(policy_holders)::text LIKE '%1980-01-01%'",
                Integer.class))
        .isZero();

    Map<String, Object> holderBeforeRerun =
        targetJdbc.queryForMap(
            "SELECT last_updated, date_of_birth_enc, ssn_last4_enc, tax_id_enc, "
                + "credit_score_enc, tax_id_bidx FROM pas.policy_holders "
                + "WHERE cust_id = 'C000000001'");
    double appliedHoldersBeforeRerun =
        meterRegistry
            .counter("pas.migration.bulk.rows", "table", "policy_holders", "result", "applied")
            .count();
    long rowVersion =
        targetJdbc.queryForObject(
            "SELECT row_version FROM pas.policies WHERE policy_number = 'POL000000001'",
            Long.class);
    JobExecution rerun =
        jobLauncher.run(
            bulkLoadJob,
            new JobParametersBuilder().addLong("run.id", 52902L, true).toJobParameters());

    assertThat(rerun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(
            targetJdbc.queryForObject(
                "SELECT row_version FROM pas.policies WHERE policy_number = 'POL000000001'",
                Long.class))
        .isEqualTo(rowVersion);
    Map<String, Object> holderAfterRerun =
        targetJdbc.queryForMap(
            "SELECT last_updated, date_of_birth_enc, ssn_last4_enc, tax_id_enc, "
                + "credit_score_enc, tax_id_bidx FROM pas.policy_holders "
                + "WHERE cust_id = 'C000000001'");
    assertThat(holderAfterRerun.get("last_updated"))
        .isEqualTo(holderBeforeRerun.get("last_updated"));
    for (String column :
        new String[] {
          "date_of_birth_enc", "ssn_last4_enc", "tax_id_enc", "credit_score_enc", "tax_id_bidx"
        }) {
      assertThat((byte[]) holderAfterRerun.get(column))
          .containsExactly((byte[]) holderBeforeRerun.get(column));
    }
    assertThat(
            meterRegistry
                .counter("pas.migration.bulk.rows", "table", "policy_holders", "result", "applied")
                .count())
        .isEqualTo(appliedHoldersBeforeRerun);

    byte[] originalTaxIdCiphertext = (byte[]) holderAfterRerun.get("tax_id_enc");
    byte[] originalTaxIdIndex = (byte[]) holderAfterRerun.get("tax_id_bidx");
    sourceJdbc.update(
        "UPDATE acmeins.policy_holders SET tax_id = ?, "
            + "last_updated = last_updated + INTERVAL '1' SECOND WHERE cust_id = ?",
        "TAX0000099",
        "C000000001");
    JobExecution changedPii =
        jobLauncher.run(
            bulkLoadJob,
            new JobParametersBuilder().addLong("run.id", 52903L, true).toJobParameters());

    assertThat(changedPii.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    Map<String, Object> changedHolder =
        targetJdbc.queryForMap(
            "SELECT tax_id_enc, tax_id_bidx FROM pas.policy_holders "
                + "WHERE cust_id = 'C000000001'");
    assertThat((byte[]) changedHolder.get("tax_id_enc")).isNotEqualTo(originalTaxIdCiphertext);
    assertThat((byte[]) changedHolder.get("tax_id_bidx")).isNotEqualTo(originalTaxIdIndex);
    assertThat(
            PolicyHolderPii.decryptString(
                (byte[]) changedHolder.get("tax_id_enc"),
                cipher,
                new PiiContext("policy_holders", "tax_id_enc", "C000000001")))
        .isEqualTo("TAX0000099");
    assertThat(
            targetJdbc.queryForObject(
                "SELECT count(*) FROM pas.policies WHERE policy_number = 'POL000000001'",
                Integer.class))
        .isEqualTo(1);
    assertThat(targetJdbc.queryForObject("SELECT count(*) FROM pas.policy_holders", Integer.class))
        .isEqualTo(2);
    assertAllRowsLoaded();
  }

  private void assertAllRowsLoaded() {
    for (MigrationTable table : MigrationTable.bulkLoadOrder()) {
      Integer sourceCount =
          sourceJdbc.queryForObject(
              "SELECT count(*) FROM acmeins." + table.sourceName(), Integer.class);
      Integer targetCount =
          targetJdbc.queryForObject(
              "SELECT count(*) FROM pas." + table.targetName(), Integer.class);
      assertThat(targetCount).as(table.sourceName()).isEqualTo(sourceCount);
    }
  }
}
