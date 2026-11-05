package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.persistence.DeliveryReceipt;
import com.acme.insurance.pas.persistence.ExtractJdbcRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** End to end: PostgreSQL, both jobs, a real SFTP server, receipts, purge and failure alerting. */
@SpringBootTest(
    properties = {
      "spring.batch.job.enabled=false",
      "spring.batch.jdbc.initialize-schema=always",
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:db/migration/postgresql"
    })
@Import(ExtractItSupport.Postgres.class)
@ExtendWith(OutputCaptureExtension.class)
class ExtractJobsIT {

  static final ExtractItSupport.SftpServerFixture SFTP = new ExtractItSupport.SftpServerFixture();
  static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 5);

  @DynamicPropertySource
  static void sftp(DynamicPropertyRegistry registry) {
    SFTP.register(registry);
  }

  @Autowired JobLauncher launcher;

  @Autowired
  @Qualifier(ExtractJobsConfiguration.CLAIMS_JOB)
  Job claims;

  @Autowired
  @Qualifier(ExtractJobsConfiguration.ACTUARIAL_JOB)
  Job actuarial;

  @Autowired JdbcClient jdbc;
  @Autowired ExtractJdbcRepository receipts;

  ExtractFixtures fixtures;
  int run;

  @BeforeEach
  void seed() {
    fixtures = new ExtractFixtures(jdbc);
    fixtures.reset();
    fixtures.seedBook(BUSINESS_DATE);
  }

  @AfterEach
  void restore() {
    SFTP.restoreHostKey();
  }

  JobExecution launch(Job job, LocalDate date) throws Exception {
    return launcher.run(
        job,
        new JobParametersBuilder()
            .addString(BusinessDateParameters.BUSINESS_DATE, date.toString())
            .addLong("run", System.nanoTime() + run++)
            .toJobParameters());
  }

  @Test
  void claimsJobDeliversFixedWidthFilesWithManifestsAndReceipts() throws Exception {
    JobExecution execution = launch(claims, BUSINESS_DATE);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    byte[] policy = Files.readAllBytes(SFTP.root.resolve("claims/import/policy_extract.dat"));
    byte[] coverage = Files.readAllBytes(SFTP.root.resolve("claims/import/coverage_extract.dat"));
    // Delta: AC/PN updated since midnight of the day before; CN and older rows are not selected.
    assertThat(records(policy, 200))
        .extracting(r -> r.substring(0, 12))
        .containsExactly("POL000000001", "POL000000002");
    // Full dump of active coverages, ordered by policy and sequence.
    assertThat(records(coverage, 150))
        .extracting(r -> r.substring(0, 18))
        .containsExactly(
            "POL000000001+00001",
            "POL000000001+00002",
            "POL000000002+00001",
            "POL000000002+00002",
            "POL000000003+00001",
            "POL000000004+00001",
            "POL000000005+00002");
    assertThat(records(policy, 200).get(0))
        .startsWith(
            "POL000000001AUTAC2026-01-012027-01-01H000000001AG0001NE01+000001234.56"
                + "+0000500.00+00000250000.00AP+00042");
    assertThat(records(policy, 200).get(1).substring(47, 104))
        .as("NULL agent, premium and risk score are spaces")
        .isEqualTo(
            " ".repeat(6)
                + "NE01"
                + " ".repeat(13)
                + "+0000500.00+00000250000.00AP"
                + " ".repeat(6));

    for (String name : List.of("policy_extract.dat", "coverage_extract.dat")) {
      byte[] data = Files.readAllBytes(SFTP.root.resolve("claims/import/" + name));
      String manifest = Files.readString(SFTP.root.resolve("claims/import/" + name + ".sha256"));
      assertThat(manifest).isEqualTo(sha256(data) + "  " + name + "\n");
      assertThat(SFTP.root.resolve("claims/import/" + name + ".part")).doesNotExist();
    }
    List<DeliveryReceipt> policyReceipts = receipts.findDeliveries("claims-policy", BUSINESS_DATE);
    assertThat(policyReceipts)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo(DeliveryReceipt.Status.CONFIRMED);
              assertThat(r.recordCount()).isEqualTo(2);
              assertThat(r.byteCount()).isEqualTo(policy.length);
              assertThat(r.sha256()).isEqualTo(sha256(policy));
              assertThat(r.destination())
                  .startsWith("sftp://127.0.0.1:")
                  .endsWith("/claims/import/policy_extract.dat");
              assertThat(r.confirmedAt()).isNotNull();
            });
    try (var staged = Files.list(SFTP.dir.resolve("staging"))) {
      assertThat(staged).as("staged PII is purged after confirmed delivery").isEmpty();
    }
  }

  @Test
  void actuarialJobDeliversTheExposureCsv() throws Exception {
    JobExecution execution = launch(actuarial, BUSINESS_DATE);
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String csv =
        Files.readString(
            SFTP.root.resolve("actuarial/data/exposure_monthly.csv"), StandardCharsets.ISO_8859_1);
    assertThat(csv.split("\r\n", -1))
        .containsExactly(
            "POLICY_TYPE,BRANCH_CODE,COVERAGE_TYPE,RATING_TERRITORY,CLASS_CODE,POLICY_COUNT,"
                + "TOTAL_EXPOSURE,TOTAL_PREMIUM,TOTAL_DEDUCTIBLE",
            "AUT,NE01,BI,CT0001,A1001,2,200000.00,800.10,500.00",
            "AUT,NE01,COLL,,A1002,1,,200.50,250.00",
            "AUT,NE01,PD,CT0001,,1,50000.00,120.00,250.00",
            "AUT,NE01,PD,CT0003,A1003,1,25000.00,75.25,250.00",
            "");
    assertThat(receipts.findDeliveries("actuarial-exposure", BUSINESS_DATE))
        .singleElement()
        .extracting(DeliveryReceipt::status)
        .isEqualTo(DeliveryReceipt.Status.CONFIRMED);
  }

  @Test
  void anUnknownHostKeyFailsTheJobRecordsTheFailureAndAlerts(CapturedOutput output)
      throws Exception {
    SFTP.impersonate();
    JobExecution execution = launch(claims, BUSINESS_DATE);

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(receipts.findDeliveries("claims-policy", BUSINESS_DATE))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo(DeliveryReceipt.Status.FAILED);
              assertThat(r.attempts()).isEqualTo(2);
              assertThat(r.confirmedAt()).isNull();
            });
    assertThat(output.getOut())
        .contains("pas.extract.alert")
        .contains(ExtractJobsConfiguration.CLAIMS_JOB)
        .doesNotContain("POL000000001");
  }

  @Test
  void aMissingBusinessDateIsDefaultedAndAMalformedOneIsRejected() {
    BusinessDateParameters parameters =
        (BusinessDateParameters) claims.getJobParametersIncrementer();
    assertThat(parameters.getNext(null).getString(BusinessDateParameters.BUSINESS_DATE))
        .matches("\\d{4}-\\d{2}-\\d{2}");
    assertThatThrownBy(
            () ->
                launcher.run(
                    claims,
                    new JobParametersBuilder()
                        .addString(BusinessDateParameters.BUSINESS_DATE, "05/11/2026")
                        .toJobParameters()))
        .isInstanceOf(JobParametersInvalidException.class);
  }

  static List<String> records(byte[] file, int lrecl) {
    String text = new String(file, StandardCharsets.ISO_8859_1);
    assertThat(text).endsWith("\r\n");
    List<String> records = List.of(text.substring(0, text.length() - 2).split("\r\n", -1));
    assertThat(records).allSatisfy(r -> assertThat(r).hasSize(lrecl));
    return records;
  }

  static String sha256(byte[] data) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
  }
}
