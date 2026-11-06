package com.acme.insurance.pas.batch.extract;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared fixtures for the extract integration tests: PostgreSQL with the pas-persistence
 * migrations, and an embedded SFTP server that accepts one generated client key only (no password).
 * Keys and known_hosts are generated per run into a temporary directory; nothing is committed.
 */
final class ExtractItSupport {

  private ExtractItSupport() {}

  @TestConfiguration(proxyBeanMethods = false)
  static class Postgres {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
  }

  static final String[] PROPERTIES = {
    "spring.batch.job.enabled=false",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration/postgresql"
  };

  /**
   * How the server damages the next {@code .part} uploads, as a faulty endpoint or network would.
   */
  enum Tamper {
    NONE,
    CORRUPT,
    TRUNCATE
  }

  /**
   * An SFTP endpoint with pinned host key and key-only authentication. {@code posixRename=false}
   * advertises only fsync among the OpenSSH extensions, so clients take the plain-rename fallback.
   */
  static final class SftpServerFixture {
    final Path dir;
    final Path root;
    final Path clientKeyFile;
    final Path knownHostsFile;
    final SshServer server;
    final KeyPair hostKey;
    final AtomicReference<KeyPair> acceptedHostKey;
    final AtomicReference<Tamper> tamper = new AtomicReference<>(Tamper.NONE);

    SftpServerFixture() {
      this(true);
    }

    SftpServerFixture(boolean posixRename) {
      try {
        dir = Files.createTempDirectory("pas-extract-it");
        root = Files.createDirectories(dir.resolve("sftp-root"));
        Files.createDirectories(root.resolve("claims/import"));
        Files.createDirectories(root.resolve("actuarial/data"));
        KeyPair clientKey = KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256);
        hostKey = KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256);
        acceptedHostKey = new AtomicReference<>(hostKey);
        clientKeyFile = dir.resolve("id_ecdsa");
        try (OutputStream out = Files.newOutputStream(clientKeyFile)) {
          OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "pas-it", null, out);
        }
        PublicKey clientPublic = clientKey.getPublic();
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(session -> List.of(acceptedHostKey.get()));
        server.setPublickeyAuthenticator(
            (user, key, session) ->
                "pas-batch".equals(user) && KeyUtils.compareKeys(key, clientPublic));
        server.setPasswordAuthenticator(null);
        server.setKeyboardInteractiveAuthenticator(null);
        server.setSubsystemFactories(
            List.of(
                new SftpSubsystemFactory.Builder()
                    .withFileSystemAccessor(new TamperingAccessor(tamper))
                    .build()));
        if (!posixRename) {
          SftpModuleProperties.OPENSSH_EXTENSIONS.set(server, "fsync@openssh.com=1");
        }
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
        knownHostsFile = dir.resolve("known_hosts");
        Files.writeString(
            knownHostsFile,
            "[127.0.0.1]:"
                + server.getPort()
                + " "
                + PublicKeyEntry.toString(hostKey.getPublic())
                + "\n");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }

    /** Presents a different host key, as a man-in-the-middle would. */
    void impersonate() throws GeneralSecurityException {
      acceptedHostKey.set(KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256));
    }

    void restoreHostKey() {
      acceptedHostKey.set(hostKey);
    }

    void register(DynamicPropertyRegistry registry) {
      registry.add("pas.extract.staging-dir", () -> dir.resolve("staging").toString());
      registry.add("pas.extract.retry.max-attempts", () -> "2");
      registry.add("pas.extract.retry.backoff", () -> "PT0S");
      for (String consumer : List.of("claims", "actuarial")) {
        String remote = consumer.equals("claims") ? "/claims/import" : "/actuarial/data";
        String prefix = "pas.extract." + consumer + ".";
        registry.add(prefix + "uri", () -> "sftp://127.0.0.1:" + server.getPort() + remote);
        registry.add(prefix + "username", () -> "pas-batch");
        registry.add(prefix + "private-key-file", clientKeyFile::toString);
        registry.add(prefix + "known-hosts-file", knownHostsFile::toString);
      }
    }
  }

  /** Damages writes to {@code .part} files according to the fixture's {@link Tamper} mode. */
  private static final class TamperingAccessor implements SftpFileSystemAccessor {
    private final AtomicReference<Tamper> mode;

    TamperingAccessor(AtomicReference<Tamper> mode) {
      this.mode = mode;
    }

    @Override
    public SeekableByteChannel openFile(
        SftpSubsystemProxy subsystem,
        FileHandle fileHandle,
        Path file,
        String handle,
        Set<? extends OpenOption> options,
        FileAttribute<?>... attrs)
        throws IOException {
      SeekableByteChannel channel =
          SftpFileSystemAccessor.super.openFile(
              subsystem, fileHandle, file, handle, options, attrs);
      Tamper tamper = mode.get();
      if (tamper == Tamper.NONE
          || !options.contains(StandardOpenOption.WRITE)
          || !file.getFileName().toString().endsWith(".part")) {
        return channel;
      }
      return new TamperingChannel(channel, tamper);
    }
  }

  private static final class TamperingChannel implements SeekableByteChannel {
    private final SeekableByteChannel delegate;
    private final Tamper tamper;

    TamperingChannel(SeekableByteChannel delegate, Tamper tamper) {
      this.delegate = delegate;
      this.tamper = tamper;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      if (tamper == Tamper.CORRUPT && delegate.position() == 0 && src.hasRemaining()) {
        byte[] bytes = new byte[src.remaining()];
        src.get(bytes);
        bytes[0] ^= 0x01;
        return delegate.write(ByteBuffer.wrap(bytes));
      }
      return delegate.write(src);
    }

    @Override
    public void close() throws IOException {
      if (tamper == Tamper.TRUNCATE && delegate.isOpen() && delegate.size() > 0) {
        delegate.truncate(delegate.size() - 1);
      }
      delegate.close();
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      return delegate.read(dst);
    }

    @Override
    public long position() throws IOException {
      return delegate.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
      delegate.position(newPosition);
      return this;
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }
  }
}
