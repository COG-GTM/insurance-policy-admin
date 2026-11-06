package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.insurance.pas.batch.extract.ExtractItSupport.SftpServerFixture;
import com.acme.insurance.pas.batch.extract.ExtractItSupport.Tamper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.LocalDate;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link SftpFeedTransfer} against an embedded SFTP server: a damaged upload is never published,
 * and the plain-rename fallback (servers without OpenSSH posix-rename) replaces, restores and
 * recovers the published file.
 */
class SftpFeedTransferTest {

  static final SftpServerFixture POSIX = new SftpServerFixture(true);
  static final SftpServerFixture PLAIN = new SftpServerFixture(false);
  static final LocalDate DAY = LocalDate.of(2026, 11, 5);

  @TempDir Path dir;
  final SftpFeedTransfer transfer = new SftpFeedTransfer();

  @AfterEach
  void reset() {
    POSIX.tamper.set(Tamper.NONE);
    PLAIN.tamper.set(Tamper.NONE);
  }

  @AfterAll
  static void stop() throws IOException {
    POSIX.server.stop(true);
    PLAIN.server.stop(true);
  }

  static SftpServerFixture server(boolean posixRename) {
    return posixRename ? POSIX : PLAIN;
  }

  static SftpDestination destination(SftpServerFixture sftp) {
    return new SftpDestination(
        URI.create("sftp://127.0.0.1:" + sftp.server.getPort() + "/claims/import"),
        "pas-batch",
        sftp.clientKeyFile,
        null,
        sftp.knownHostsFile,
        Duration.ofSeconds(10));
  }

  StagedFile stage(String name, String content) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
    Path path = Files.write(dir.resolve(name), bytes);
    return new StagedFile(
        ExtractFeed.CLAIMS_POLICY, DAY, path, 1, bytes.length, SftpFeedTransfer.sha256(bytes));
  }

  void deliver(SftpServerFixture sftp, StagedFile file) throws IOException {
    Path manifest = file.path().resolveSibling(file.path().getFileName() + ".sha256");
    Files.writeString(manifest, file.manifestLine(), StandardCharsets.US_ASCII);
    transfer.deliver(destination(sftp), file, manifest);
  }

  static Path published(SftpServerFixture sftp, String suffix) {
    return sftp.root.resolve("claims/import/policy_extract.dat" + suffix);
  }

  @ParameterizedTest(name = "posixRename={0} tamper={1}")
  @CsvSource({"true,CORRUPT", "true,TRUNCATE", "false,CORRUPT", "false,TRUNCATE"})
  void aDamagedUploadIsNeverPublishedAndTheLastGoodDeliveryStays(boolean posix, Tamper tamper)
      throws IOException {
    SftpServerFixture sftp = server(posix);
    StagedFile good = stage("good", "GOOD-DELIVERY-" + tamper + "\r\n");
    deliver(sftp, good);
    String goodManifest = Files.readString(published(sftp, ".sha256"));

    sftp.tamper.set(tamper);
    StagedFile next = stage("next", "NEXT-DELIVERY-" + tamper + "\r\n");
    assertThatThrownBy(() -> deliver(sftp, next))
        .isInstanceOf(FeedTransfer.DeliveryException.class)
        .hasMessageContaining("policy_extract.dat.part")
        .hasMessageContaining(tamper == Tamper.CORRUPT ? "SHA-256" : "remote size");

    assertThat(published(sftp, "")).hasContent("GOOD-DELIVERY-" + tamper + "\r\n");
    assertThat(Files.readString(published(sftp, ".sha256"))).isEqualTo(goodManifest);
    assertThat(published(sftp, ".part")).doesNotExist();
    assertThat(published(sftp, ".prev")).doesNotExist();

    sftp.tamper.set(Tamper.NONE);
    deliver(sftp, next);
    assertThat(published(sftp, "")).hasContent("NEXT-DELIVERY-" + tamper + "\r\n");
  }

  @Test
  void withoutPosixRenameTheFallbackReplacesThePublishedFile() throws Exception {
    try (Sftp session = Sftp.open(PLAIN)) {
      assertThat(session.client.getExtension(OpenSSHPosixRenameExtension.class).isSupported())
          .isFalse();
    }
    deliver(PLAIN, stage("first", "FIRST\r\n"));
    deliver(PLAIN, stage("second", "SECOND\r\n"));

    assertThat(published(PLAIN, "")).hasContent("SECOND\r\n");
    assertThat(published(PLAIN, ".sha256")).content().endsWith("  policy_extract.dat\n");
    assertThat(published(PLAIN, ".prev")).doesNotExist();
    assertThat(published(PLAIN, ".part")).doesNotExist();
  }

  @Test
  void theFallbackRestoresThePreviousFileWhenTheFinalRenameFails() throws Exception {
    deliver(PLAIN, stage("kept", "KEPT\r\n"));
    try (Sftp session = Sftp.open(PLAIN)) {
      assertThatThrownBy(
              () ->
                  SftpFeedTransfer.replace(
                      session.client,
                      "/claims/import/missing.part",
                      "/claims/import/policy_extract.dat"))
          .isInstanceOf(IOException.class);
    }
    assertThat(published(PLAIN, "")).hasContent("KEPT\r\n");
    assertThat(published(PLAIN, ".prev")).doesNotExist();
  }

  @Test
  void theFallbackRecoversAnInterruptedAttempt() throws Exception {
    deliver(PLAIN, stage("before", "BEFORE\r\n"));
    // An earlier attempt died between moving the published file aside and the final rename.
    Files.move(published(PLAIN, ""), published(PLAIN, ".prev"));
    try (Sftp session = Sftp.open(PLAIN)) {
      assertThatThrownBy(
              () ->
                  SftpFeedTransfer.replace(
                      session.client,
                      "/claims/import/missing.part",
                      "/claims/import/policy_extract.dat"))
          .isInstanceOf(IOException.class);
    }
    assertThat(published(PLAIN, "")).hasContent("BEFORE\r\n");
    assertThat(published(PLAIN, ".prev")).doesNotExist();

    Files.move(published(PLAIN, ""), published(PLAIN, ".prev"));
    deliver(PLAIN, stage("after", "AFTER\r\n"));
    assertThat(published(PLAIN, "")).hasContent("AFTER\r\n");
    assertThat(published(PLAIN, ".prev")).doesNotExist();
  }

  /** A raw SFTP session with the fixture's client key, for driving {@code replace} directly. */
  private record Sftp(SshClient ssh, ClientSession session, SftpClient client)
      implements AutoCloseable {

    static Sftp open(SftpServerFixture sftp) throws IOException, GeneralSecurityException {
      SshClient ssh = SshClient.setUpDefaultClient();
      ssh.setServerKeyVerifier(
          new KnownHostsServerKeyVerifier(
              RejectAllServerKeyVerifier.INSTANCE, sftp.knownHostsFile));
      ssh.start();
      ClientSession session =
          ssh.connect("pas-batch", "127.0.0.1", sftp.server.getPort())
              .verify(Duration.ofSeconds(10))
              .getSession();
      try (InputStream in = Files.newInputStream(sftp.clientKeyFile)) {
        for (KeyPair key :
            SecurityUtils.loadKeyPairIdentities(
                null, NamedResource.ofName("it"), in, FilePasswordProvider.EMPTY)) {
          session.addPublicKeyIdentity(key);
        }
      }
      session.auth().verify(Duration.ofSeconds(10));
      return new Sftp(ssh, session, SftpClientFactory.instance().createSftpClient(session));
    }

    @Override
    public void close() throws IOException {
      client.close();
      session.close();
      ssh.stop();
    }
  }
}
