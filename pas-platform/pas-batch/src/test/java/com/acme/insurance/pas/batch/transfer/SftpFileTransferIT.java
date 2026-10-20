package com.acme.insurance.pas.batch.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.batch.support.SftpTestServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SftpFileTransfer against a real OpenSSH SFTP server (Testcontainers {@code atmoz/sftp}). */
class SftpFileTransferIT {

  private static SftpTestServer server;

  @TempDir Path dir;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<Duration> sleeps = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    server = new SftpTestServer().start();
  }

  @AfterAll
  static void stop() {
    server.close();
  }

  private SftpFileTransfer transfer(Path key, Path knownHosts) {
    TransferProperties.Destination claims =
        new TransferProperties.Destination(
            server.host(),
            server.port(),
            SftpTestServer.USER,
            key.toString(),
            null,
            knownHosts.toString(),
            SftpTestServer.REMOTE_DIR);
    TransferProperties props =
        new TransferProperties(
            true,
            Duration.ofSeconds(20),
            Duration.ofSeconds(20),
            new TransferProperties.Retry(3, Duration.ofMillis(10), 2, Duration.ofSeconds(1)),
            Map.of("claims", claims));
    return new SftpFileTransfer(props, meters, sleeps::add);
  }

  private DeliveryFile file(String name, String content) throws Exception {
    Path local = Files.writeString(dir.resolve(name), content, StandardCharsets.ISO_8859_1);
    return new DeliveryFile(local, name);
  }

  @Test
  void deliversVerifiesAndConfirmsAndRerunsIdempotently() throws Exception {
    SftpFileTransfer transfer = transfer(server.writeClientKey(dir), server.writeKnownHosts(dir));
    DeliveryFile policy = file("policy_extract.dat", "A".repeat(200) + "\r\n");
    DeliveryFile coverage = file("coverage_extract.dat", "B".repeat(150) + "\r\n");

    List<DeliveryReceipt> receipts = transfer.deliver("claims", List.of(policy, coverage));

    assertThat(receipts)
        .extracting(DeliveryReceipt::remotePath)
        .containsExactly("/upload/policy_extract.dat", "/upload/coverage_extract.dat");
    assertThat(server.read("policy_extract.dat")).isEqualTo(Files.readAllBytes(policy.localPath()));
    String sha = receipts.get(0).sha256();
    assertThat(new String(server.read("policy_extract.dat.sha256"), StandardCharsets.US_ASCII))
        .isEqualTo(sha + "  policy_extract.dat\n");
    assertThat(new String(server.read("policy_extract.dat.done"), StandardCharsets.US_ASCII))
        .contains("sha256=" + sha)
        .contains("bytes=202");
    assertThat(server.list()).noneMatch(n -> n.endsWith(".part"));

    Files.writeString(policy.localPath(), "C".repeat(200) + "\r\n", StandardCharsets.ISO_8859_1);
    List<DeliveryReceipt> rerun = transfer.deliver("claims", List.of(policy));
    assertThat(server.read("policy_extract.dat")).isEqualTo(Files.readAllBytes(policy.localPath()));
    assertThat(rerun.get(0).sha256()).isNotEqualTo(sha);
    assertThat(sleeps).isEmpty();
    assertThat(
            meters
                .counter("pas.transfer.deliveries", "destination", "claims", "outcome", "success")
                .count())
        .isEqualTo(2);
  }

  @Test
  void rejectsChangedHostKeyAfterRetries() throws Exception {
    SftpFileTransfer transfer =
        transfer(server.writeClientKey(dir), server.writeWrongKnownHosts(dir));
    DeliveryFile f = file("hostkey.dat", "X\r\n");

    assertThatThrownBy(() -> transfer.deliver("claims", List.of(f)))
        .isInstanceOf(FileDeliveryException.class)
        .hasMessageContaining("3 attempt(s)");
    assertThat(sleeps).containsExactly(Duration.ofMillis(10), Duration.ofMillis(20));
    assertThat(
            meters
                .counter("pas.transfer.deliveries", "destination", "claims", "outcome", "failure")
                .count())
        .isEqualTo(1);
    assertThat(server.list()).doesNotContain("hostkey.dat");
  }

  @Test
  void rejectsUnknownHost() throws Exception {
    Path empty = Files.writeString(dir.resolve("known_hosts_empty"), "");
    SftpFileTransfer transfer = transfer(server.writeClientKey(dir), empty);
    DeliveryFile f = file("unknown.dat", "X\r\n");

    assertThatThrownBy(() -> transfer.deliver("claims", List.of(f)))
        .isInstanceOf(FileDeliveryException.class);
    assertThat(server.list()).doesNotContain("unknown.dat");
  }

  @Test
  void rejectsUntrustedClientKey() throws Exception {
    SftpFileTransfer transfer =
        transfer(SftpTestServer.writeUntrustedKey(dir), server.writeKnownHosts(dir));
    DeliveryFile f = file("untrusted.dat", "X\r\n");

    assertThatThrownBy(() -> transfer.deliver("claims", List.of(f)))
        .isInstanceOf(FileDeliveryException.class);
    assertThat(server.list()).doesNotContain("untrusted.dat");
  }
}
