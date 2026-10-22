package com.acme.insurance.pas.batch.extract.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class MinaSftpRemoteFileStoreIT {
  @TempDir Path directory;

  private SshServer server;
  private Path remoteRoot;
  private Path knownHosts;
  private KeyPair clientKey;

  @BeforeEach
  void startSftpServer() throws Exception {
    remoteRoot = Files.createDirectory(directory.resolve("remote"));
    Files.createDirectories(remoteRoot.resolve("claims/import"));
    Path hostKeyPath = directory.resolve("host-key");
    knownHosts = directory.resolve("known-hosts");

    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    clientKey = generator.generateKeyPair();
    Path clientKeyPath = directory.resolve("client-key");
    try (var output = Files.newOutputStream(clientKeyPath)) {
      OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
          clientKey, "pas-batch-test", (OpenSSHKeyEncryptionContext) null, output);
    }

    server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));
    server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
    server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
    server.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
    server.setPublickeyAuthenticator((username, key, session) -> key.equals(clientKey.getPublic()));
    server.start();

    KeyPair hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
    Files.writeString(
        knownHosts,
        "[127.0.0.1]:"
            + server.getPort()
            + " "
            + PublicKeyEntry.toString(hostKey.getPublic())
            + "\n");
  }

  @AfterEach
  void stopSftpServer() throws Exception {
    if (server != null) {
      server.stop(true);
    }
  }

  @Test
  void uploadsWholeSetAndAtomicallyReplacesExistingRemoteFiles() throws Exception {
    Path policy = Files.writeString(directory.resolve("policy.dat"), "policy bytes");
    Path coverage = Files.writeString(directory.resolve("coverage.dat"), "coverage bytes");
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ExtractDeliveryService service = service(jdbcTemplate, knownHosts, ignored -> {});
    List<ExtractDeliveryService.DeliveryFile> files =
        List.of(
            new ExtractDeliveryService.DeliveryFile(
                policy, "policy_extract.dat", "daily-policy/v1", 1),
            new ExtractDeliveryService.DeliveryFile(
                coverage, "coverage_extract.dat", "daily-coverage/v1", 1));

    service.deliver("claims", "dailyExtractJob", 42, LocalDate.of(2025, 2, 10), files);
    service.deliver("claims", "dailyExtractJob", 43, LocalDate.of(2025, 2, 10), files);

    Path remotePolicy = remoteRoot.resolve("claims/import/policy_extract.dat");
    Path remoteCoverage = remoteRoot.resolve("claims/import/coverage_extract.dat");
    assertThat(Files.readString(remotePolicy)).isEqualTo("policy bytes");
    assertThat(Files.readString(remoteCoverage)).isEqualTo("coverage bytes");
    assertThat(Files.readString(Path.of(remotePolicy + ".done")))
        .contains("layout=daily-policy/v1")
        .contains("records=1")
        .contains("bytes=12")
        .contains("businessDate=2025-02-10")
        .contains("jobExecutionId=43");
    String checksum =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("coverage bytes".getBytes(StandardCharsets.UTF_8)));
    assertThat(Files.readString(Path.of(remoteCoverage + ".sha256")))
        .isEqualTo(checksum + "  coverage_extract.dat\n");
    try (var filesOnServer = Files.walk(remoteRoot)) {
      assertThat(filesOnServer.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.endsWith(".tmp"));
    }
    verify(jdbcTemplate, times(4)).update(anyString(), any(Object[].class));
  }

  @Test
  void rejectsAChangedHostKeyWithoutRetrying() throws Exception {
    Path policy = Files.writeString(directory.resolve("untrusted-policy.dat"), "policy bytes");
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    KeyPair untrustedHostKey = generator.generateKeyPair();
    Path untrustedHosts =
        Files.writeString(
            directory.resolve("untrusted-known-hosts"),
            "[127.0.0.1]:"
                + server.getPort()
                + " "
                + PublicKeyEntry.toString(untrustedHostKey.getPublic())
                + "\n");
    AtomicInteger backoffs = new AtomicInteger();
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ExtractDeliveryService service =
        service(jdbcTemplate, untrustedHosts, ignored -> backoffs.incrementAndGet());
    var file =
        new ExtractDeliveryService.DeliveryFile(policy, "policy_extract.dat", "daily-policy/v1", 1);

    assertThatThrownBy(
            () ->
                service.deliver(
                    "claims", "dailyExtractJob", 44, LocalDate.of(2025, 2, 10), List.of(file)))
        .isInstanceOf(TransferSecurityException.class)
        .hasMessageContaining("SFTP server host key was rejected");

    assertThat(backoffs).hasValue(0);
    assertThat(Files.exists(remoteRoot.resolve("claims/import/policy_extract.dat"))).isFalse();
    verify(jdbcTemplate).update(anyString(), any(Object[].class));
  }

  private ExtractDeliveryService service(
      JdbcTemplate jdbcTemplate, Path hostFile, ExtractDeliveryService.Sleeper sleeper) {
    SftpProperties properties = new SftpProperties();
    Map<String, SftpProperties.Target> targets = new HashMap<>();
    targets.put(
        "claims",
        new SftpProperties.Target(
            "127.0.0.1",
            server.getPort(),
            "claims",
            directory.resolve("client-key").toString(),
            null,
            hostFile.toString(),
            "/claims/import",
            Duration.ofSeconds(5)));
    properties.setTargets(targets);
    return new ExtractDeliveryService(
        new MinaSftpRemoteFileStore(),
        properties,
        new ExtractRetryProperties(3, Duration.ofMillis(1), 2, Duration.ofSeconds(1)),
        jdbcTemplate,
        java.time.Clock.systemUTC(),
        sleeper);
  }
}
