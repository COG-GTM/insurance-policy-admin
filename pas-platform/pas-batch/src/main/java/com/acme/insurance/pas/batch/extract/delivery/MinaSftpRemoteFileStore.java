package com.acme.insurance.pas.batch.extract.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

@Component
public class MinaSftpRemoteFileStore implements RemoteFileStore {
  @Override
  public Session connect(SftpProperties.Target target) throws IOException {
    validate(target);
    SshClient client = SshClient.setUpDefaultClient();
    AtomicBoolean hostKeyRejected = new AtomicBoolean();
    ClientSession session = null;
    SftpClient sftp = null;
    try {
      KnownHostsServerKeyVerifier knownHostsVerifier =
          new KnownHostsServerKeyVerifier(
              RejectAllServerKeyVerifier.INSTANCE, Path.of(target.knownHostsFile()));
      client.setServerKeyVerifier(
          (clientSession, remoteAddress, serverKey) -> {
            boolean accepted;
            try {
              accepted =
                  knownHostsVerifier.verifyServerKey(clientSession, remoteAddress, serverKey);
            } catch (RuntimeException exception) {
              hostKeyRejected.set(true);
              throw exception;
            }
            if (!accepted) {
              hostKeyRejected.set(true);
            }
            return accepted;
          });
      client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
      client.start();
      Duration timeout =
          target.connectTimeout() == null ? Duration.ofSeconds(30) : target.connectTimeout();
      session =
          client
              .connect(target.username(), target.host(), target.port())
              .verify(timeout)
              .getSession();
      Iterable<KeyPair> keys;
      try {
        char[] passphrase = passphrase(target.keyPassphraseFile());
        Path privateKey = Path.of(target.privateKeyFile());
        try (InputStream keyStream = Files.newInputStream(privateKey)) {
          keys =
              SecurityUtils.loadKeyPairIdentities(
                  null,
                  NamedResource.ofName(privateKey.toString()),
                  keyStream,
                  FilePasswordProvider.of(passphrase == null ? null : new String(passphrase)));
        }
      } catch (IOException | GeneralSecurityException | RuntimeException exception) {
        throw new TransferSecurityException("SFTP client key could not be loaded", exception);
      }
      for (KeyPair key : keys) {
        session.addPublicKeyIdentity(key);
      }
      try {
        session.auth().verify(timeout);
      } catch (IOException exception) {
        if (hostKeyRejected.get()) {
          throw new TransferSecurityException("SFTP server host key was rejected", exception);
        }
        throw new TransferSecurityException("SFTP public-key authentication failed", exception);
      }
      sftp = SftpClientFactory.instance().createSftpClient(session);
      return new MinaSession(client, session, sftp);
    } catch (TransferSecurityException exception) {
      closeQuietly(sftp, session, client);
      throw exception;
    } catch (IOException | RuntimeException exception) {
      closeQuietly(sftp, session, client);
      if (hostKeyRejected.get()) {
        throw new TransferSecurityException("SFTP server host key was rejected", exception);
      }
      throw exception;
    }
  }

  private static void validate(SftpProperties.Target target) throws TransferSecurityException {
    if (target == null
        || blank(target.host())
        || target.port() < 1
        || blank(target.username())
        || blank(target.privateKeyFile())
        || blank(target.knownHostsFile())
        || blank(target.remoteDir())) {
      throw new TransferSecurityException("SFTP target configuration is incomplete", null);
    }
    if (!Files.isRegularFile(Path.of(target.privateKeyFile()))
        || !Files.isRegularFile(Path.of(target.knownHostsFile()))) {
      throw new TransferSecurityException("SFTP key or known-hosts file is unavailable", null);
    }
  }

  private static char[] passphrase(String fileName) throws IOException {
    if (blank(fileName)) {
      return null;
    }
    return Files.readString(Path.of(fileName)).strip().toCharArray();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static void closeQuietly(SftpClient sftp, ClientSession session, SshClient client) {
    try {
      if (sftp != null) {
        sftp.close();
      }
    } catch (IOException ignored) {
      // Best-effort cleanup after setup failure.
    }
    try {
      if (session != null) {
        session.close();
      }
    } catch (IOException ignored) {
      // Best-effort cleanup after setup failure.
    }
    client.stop();
  }

  private static final class MinaSession implements Session {
    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;

    private MinaSession(SshClient client, ClientSession session, SftpClient sftp) {
      this.client = client;
      this.session = session;
      this.sftp = sftp;
    }

    @Override
    public void delete(String remotePath) throws IOException {
      try {
        sftp.remove(remotePath);
      } catch (org.apache.sshd.sftp.common.SftpException exception) {
        if (exception.getStatus()
            != org.apache.sshd.sftp.common.SftpConstants.SSH_FX_NO_SUCH_FILE) {
          throw exception;
        }
      }
    }

    @Override
    public void upload(Path localFile, String remotePath) throws IOException {
      try (SftpClient.CloseableHandle handle =
          sftp.open(
              remotePath,
              SftpClient.OpenMode.Write,
              SftpClient.OpenMode.Create,
              SftpClient.OpenMode.Truncate)) {
        try (var input = Files.newInputStream(localFile)) {
          byte[] buffer = new byte[8192];
          long offset = 0;
          int read;
          while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
              sftp.write(handle, offset, buffer, 0, read);
              offset += read;
            }
          }
        }
      }
    }

    @Override
    public long size(String remotePath) throws IOException {
      return sftp.stat(remotePath).getSize();
    }

    @Override
    public void rename(String source, String destination) throws IOException {
      try {
        sftp.rename(source, destination, SftpClient.CopyMode.Atomic, SftpClient.CopyMode.Overwrite);
      } catch (org.apache.sshd.sftp.common.SftpException exception) {
        if (exception.getStatus()
            != org.apache.sshd.sftp.common.SftpConstants.SSH_FX_OP_UNSUPPORTED) {
          throw exception;
        }
        delete(destination);
        sftp.rename(source, destination);
      }
    }

    @Override
    public void write(byte[] content, String remotePath) throws IOException {
      Path file = Files.createTempFile("pas-extract-sidecar-", ".tmp");
      try {
        Files.write(file, content);
        upload(file, remotePath);
      } finally {
        Files.deleteIfExists(file);
      }
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      try {
        sftp.close();
      } catch (IOException exception) {
        failure = exception;
      }
      try {
        session.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        }
      } finally {
        client.stop();
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
