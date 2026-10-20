package com.acme.insurance.pas.batch.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * An OpenSSH SFTP server ({@code atmoz/sftp}) with user {@code pas}, public-key auth only, and a
 * writable chroot directory {@code /upload}. Key material is generated per test run.
 */
public final class SftpTestServer implements AutoCloseable {

  public static final String USER = "pas";
  public static final String REMOTE_DIR = "/upload";
  private static final String HOME = "/home/" + USER;

  private static final String DISABLE_PENALTIES =
      """
      #!/bin/sh
      cp /etc/ssh/sshd_config /tmp/sshd_config.orig
      echo 'PerSourcePenalties no' >> /etc/ssh/sshd_config
      /usr/sbin/sshd -t 2>/dev/null || cp /tmp/sshd_config.orig /etc/ssh/sshd_config
      """;

  private final KeyPair clientKey;
  private final GenericContainer<?> container;

  public SftpTestServer() throws GeneralSecurityException, IOException {
    this.clientKey = ecKey();
    ByteArrayOutputStream pub = new ByteArrayOutputStream();
    OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(clientKey, "pas-batch-it", pub);
    this.container =
        new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
            // user:password(empty = no password login):uid::dir
            .withCommand(USER + "::1001::upload")
            .withCopyToContainer(Transferable.of(pub.toByteArray()), HOME + "/.ssh/keys/id.pub")
            // The negative tests fail auth on purpose; OpenSSH >= 9.8 would then penalise
            // (drop) further connections from the Docker host for a while.
            .withCopyToContainer(
                Transferable.of(DISABLE_PENALTIES.getBytes(StandardCharsets.US_ASCII), 0755),
                "/etc/sftp.d/disable-penalties.sh")
            .withExposedPorts(22)
            .waitingFor(Wait.forListeningPort());
  }

  public SftpTestServer start() {
    container.start();
    return this;
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(22);
  }

  /** Writes the client private key (OpenSSH format, unencrypted) to {@code dir}. */
  public Path writeClientKey(Path dir) throws IOException, GeneralSecurityException {
    return writePrivateKey(dir.resolve("id_ecdsa"), clientKey);
  }

  /** Writes a key the server does not trust. */
  public static Path writeUntrustedKey(Path dir) throws IOException, GeneralSecurityException {
    return writePrivateKey(dir.resolve("id_untrusted"), ecKey());
  }

  /** known_hosts with the server's real host keys for {@code [host]:port}. */
  public Path writeKnownHosts(Path dir) throws IOException, InterruptedException {
    ExecResult result = container.execInContainer("sh", "-c", "cat /etc/ssh/ssh_host_*_key.pub");
    List<String> lines = new ArrayList<>();
    for (String line : result.getStdout().split("\n")) {
      String[] parts = line.trim().split("\\s+");
      if (parts.length >= 2) {
        for (String name : hostNames()) {
          lines.add("[" + name + "]:" + port() + " " + parts[0] + " " + parts[1]);
        }
      }
    }
    if (lines.isEmpty()) {
      throw new IllegalStateException("no host keys read from container: " + result.getStderr());
    }
    return Files.write(dir.resolve("known_hosts"), lines, StandardCharsets.US_ASCII);
  }

  private List<String> hostNames() throws IOException {
    List<String> names = new ArrayList<>(List.of(host()));
    for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host())) {
      if (!names.contains(address.getHostAddress())) {
        names.add(address.getHostAddress());
      }
    }
    return names;
  }

  /** known_hosts that pins a different (attacker) key for this server. */
  public Path writeWrongKnownHosts(Path dir) throws IOException, GeneralSecurityException {
    KeyPair other = ecKey();
    String entry = PublicKeyEntry.toString(other.getPublic());
    List<String> lines = new ArrayList<>();
    for (String name : hostNames()) {
      lines.add("[" + name + "]:" + port() + " " + entry);
    }
    return Files.write(dir.resolve("known_hosts_wrong"), lines, StandardCharsets.US_ASCII);
  }

  public byte[] read(String remoteFile) {
    return container.copyFileFromContainer(
        HOME + REMOTE_DIR + "/" + remoteFile, in -> in.readAllBytes());
  }

  public List<String> list() throws IOException, InterruptedException {
    String out = container.execInContainer("ls", "-1", HOME + REMOTE_DIR).getStdout();
    return out.isBlank() ? List.of() : List.of(out.trim().split("\n"));
  }

  @Override
  public void close() {
    container.stop();
  }

  private static KeyPair ecKey() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static Path writePrivateKey(Path file, KeyPair key)
      throws IOException, GeneralSecurityException {
    try (OutputStream out = Files.newOutputStream(file)) {
      OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "pas-batch-it", null, out);
    }
    return file;
  }
}
