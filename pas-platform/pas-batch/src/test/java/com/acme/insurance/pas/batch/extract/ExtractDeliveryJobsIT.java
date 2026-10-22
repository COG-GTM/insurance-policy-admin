package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    properties = {
      "spring.main.web-application-type=none",
      "pas.routing.daily-extract=new",
      "pas.routing.monthly-exposure=new"
    })
class ExtractDeliveryJobsIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final SftpFixture SFTP = new SftpFixture();

  @Autowired private JobLauncher jobLauncher;
  @Autowired private Job dailyExtractJob;
  @Autowired private Job monthlyExposureJob;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void configureServices(DynamicPropertyRegistry registry) {
    PasTestDatabase.migratePas(POSTGRES);
    try {
      SFTP.start();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to start embedded SFTP test server", exception);
    }
    registry.add("pas.batch.extract.work-dir", () -> SFTP.root.resolve("local").toString());
    for (String target : List.of("claims", "actuarial")) {
      String prefix = "pas.batch.sftp.targets." + target + ".";
      registry.add(prefix + "host", () -> "127.0.0.1");
      registry.add(prefix + "port", () -> SFTP.server.getPort());
      registry.add(prefix + "username", () -> "extract-test");
      registry.add(prefix + "private-key-file", () -> SFTP.clientKeyFile.toString());
      registry.add(prefix + "known-hosts-file", () -> SFTP.knownHosts.toString());
      registry.add(prefix + "connect-timeout", () -> "5s");
    }
  }

  @BeforeEach
  void loadFixture() {
    new ResourceDatabasePopulator(new ClassPathResource("extract/extract-fixture.sql"))
        .execute(jdbcTemplate.getDataSource());
  }

  @AfterAll
  static void stopSftpServer() throws IOException {
    SFTP.stop();
  }

  @Test
  void deliversBothJobsAndRestartsAfterAnUntrustedHostFailure(CapturedOutput capturedOutput)
      throws Exception {
    SFTP.rejectHostKey();
    JobParameters failedParameters = parameters(99);
    JobExecution failed = jobLauncher.run(dailyExtractJob, failedParameters);

    assertThat(failed.getStatus().name()).isEqualTo("FAILED");
    assertThat(Files.exists(SFTP.root.resolve("remote/claims/import/policy_extract.dat")))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.extract_delivery WHERE status = 'FAILED'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            meterRegistry
                .get("pas.batch.job.failures")
                .tag("job", "dailyExtractJob")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(capturedOutput.getOut() + capturedOutput.getErr())
        .contains("pas.batch.job.failed")
        .contains("SFTP server host key was rejected")
        .doesNotContain("AB0000000001");

    SFTP.acceptHostKey();
    JobExecution restarted = jobLauncher.run(dailyExtractJob, failedParameters);
    assertThat(restarted.getStatus().name()).isEqualTo("COMPLETED");
    assertDailyGolden(restarted);

    for (long rerun = 1; rerun <= 5; rerun++) {
      JobExecution execution = jobLauncher.run(dailyExtractJob, parameters(rerun));
      assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
      assertDailyGolden(execution);
    }

    JobExecution monthly = jobLauncher.run(monthlyExposureJob, parameters(1));
    assertThat(monthly.getStatus().name()).isEqualTo("COMPLETED");
    Path monthlyOutput = outputDirectory(monthly, "monthlyExposureJob");
    assertGolden(
        monthlyOutput.resolve("exposure_monthly.D250210.csv"),
        "extract/golden/monthly/exposure_monthly.csv");
    assertThat(Files.readString(SFTP.root.resolve("remote/actuarial/data/exposure_monthly.csv")))
        .isEqualTo("HOM,,FIRE,,,2,250000.00,14.00,1000.00\r\n");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.extract_delivery "
                    + "WHERE job_execution_id = ? AND status = 'DELIVERED'",
                Integer.class,
                monthly.getId()))
        .isEqualTo(1);
    assertThat(Files.exists(SFTP.root.resolve("remote/actuarial/data/exposure_monthly.csv.done")))
        .isTrue();
    assertNoTemporaryFiles(SFTP.root.resolve("remote"));
  }

  private void assertDailyGolden(JobExecution execution) throws Exception {
    Path output = outputDirectory(execution, "dailyExtractJob");
    assertGolden(output.resolve("policy_extract.dat"), "extract/golden/daily/policy_extract.dat");
    assertGolden(
        output.resolve("coverage_extract.dat"), "extract/golden/daily/coverage_extract.dat");
    Path remote = SFTP.root.resolve("remote/claims/import");
    assertThat(Files.readAllBytes(remote.resolve("policy_extract.dat")))
        .isEqualTo(Files.readAllBytes(output.resolve("policy_extract.dat")));
    assertThat(Files.readAllBytes(remote.resolve("coverage_extract.dat")))
        .isEqualTo(Files.readAllBytes(output.resolve("coverage_extract.dat")));
    assertThat(Files.readString(remote.resolve("policy_extract.dat.done")))
        .contains("layout=daily-policy/v1")
        .contains("jobExecutionId=" + execution.getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pas_batch.extract_delivery "
                    + "WHERE job_execution_id = ? AND status = 'DELIVERED'",
                Integer.class,
                execution.getId()))
        .isEqualTo(2);
    assertNoTemporaryFiles(remote);
  }

  private static JobParameters parameters(long rerun) {
    return new JobParametersBuilder()
        .addString("businessDate", LocalDate.of(2025, 2, 10).toString(), true)
        .addLong("rerun", rerun, true)
        .toJobParameters();
  }

  private static Path outputDirectory(JobExecution execution, String jobName) {
    return SFTP.root.resolve(Path.of("local", jobName, "2025-02-10", execution.getId().toString()));
  }

  private static void assertGolden(Path actual, String resource) throws Exception {
    byte[] expected = new ClassPathResource(resource).getInputStream().readAllBytes();
    assertThat(Files.readAllBytes(actual)).isEqualTo(expected);
  }

  private static void assertNoTemporaryFiles(Path directory) throws IOException {
    try (Stream<Path> files = Files.walk(directory)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.endsWith(".tmp"));
    }
  }

  private static final class SftpFixture {
    private Path root;
    private Path clientKeyFile;
    private Path knownHosts;
    private String pinnedHostKey;
    private KeyPair clientKey;
    private SshServer server;

    private void start() throws Exception {
      root = Files.createTempDirectory("pas-extract-delivery-it");
      Path remoteRoot = Files.createDirectories(root.resolve("remote"));
      Files.createDirectories(remoteRoot.resolve("claims/import"));
      Files.createDirectories(remoteRoot.resolve("actuarial/data"));
      clientKeyFile = root.resolve("client-key");
      knownHosts = root.resolve("known-hosts");
      Path hostKey = root.resolve("host-key");

      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      clientKey = generator.generateKeyPair();
      try (var output = Files.newOutputStream(clientKeyFile)) {
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
            clientKey, "pas-batch-test", (OpenSSHKeyEncryptionContext) null, output);
      }

      server = SshServer.setUpDefaultServer();
      server.setHost("127.0.0.1");
      server.setPort(0);
      server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
      server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
      server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
      server.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
      server.setPublickeyAuthenticator(
          (username, key, session) -> key.equals(clientKey.getPublic()));
      server.start();

      KeyPair serverKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
      pinnedHostKey =
          "[127.0.0.1]:" + server.getPort() + " " + PublicKeyEntry.toString(serverKey.getPublic());
      acceptHostKey();
    }

    private void rejectHostKey() throws Exception {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair untrustedHostKey = generator.generateKeyPair();
      Files.writeString(
          knownHosts,
          "[127.0.0.1]:"
              + server.getPort()
              + " "
              + PublicKeyEntry.toString(untrustedHostKey.getPublic())
              + "\n");
    }

    private void acceptHostKey() throws IOException {
      Files.writeString(knownHosts, pinnedHostKey + "\n");
    }

    private void stop() throws IOException {
      if (server != null) {
        server.stop(true);
      }
      if (root != null) {
        try (Stream<Path> files = Files.walk(root)) {
          for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
          }
        }
      }
    }
  }
}
