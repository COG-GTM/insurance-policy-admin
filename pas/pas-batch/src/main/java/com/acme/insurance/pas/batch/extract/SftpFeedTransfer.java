package com.acme.insurance.pas.batch.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHPosixRenameExtension;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/**
 * SFTP delivery with Apache MINA SSHD (ADR 0001 D6). Host keys must match the pinned known-hosts
 * file, the client offers public-key authentication only, files are uploaded under a {@code .part}
 * name and renamed into place, and each file is read back and its SHA-256 compared before the
 * delivery counts as confirmed. The manifest goes after the data file so its arrival signals a
 * complete delivery.
 */
public class SftpFeedTransfer implements FeedTransfer {

  @Override
  public void deliver(SftpDestination destination, StagedFile file, Path manifest) {
    try (SshClient client = SshClient.setUpDefaultClient()) {
      client.setServerKeyVerifier(
          new KnownHostsServerKeyVerifier(
              RejectAllServerKeyVerifier.INSTANCE, destination.knownHostsFile()));
      client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
      client.start();
      try (ClientSession session =
          client
              .connect(destination.username(), destination.host(), destination.port())
              .verify(destination.timeout())
              .getSession()) {
        for (KeyPair key : loadKeys(destination)) {
          session.addPublicKeyIdentity(key);
        }
        session.auth().verify(destination.timeout());
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
          String remote = destination.remotePath(file.feed().remoteName());
          put(sftp, file.path(), remote, file.sha256(), file.byteCount());
          byte[] manifestBytes = Files.readAllBytes(manifest);
          put(sftp, manifest, remote + ".sha256", sha256(manifestBytes), manifestBytes.length);
        }
      }
    } catch (IOException | GeneralSecurityException e) {
      throw new DeliveryException(
          file.feed().feedId()
              + " to "
              + destination.describe(file.feed().remoteName())
              + ": "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private static void put(SftpClient sftp, Path local, String remote, String sha256, long size)
      throws IOException {
    String partial = remote + ".part";
    try (OutputStream out = sftp.write(partial)) {
      Files.copy(local, out);
    }
    replace(sftp, partial, remote);
    long remoteSize = sftp.stat(remote).getSize();
    if (remoteSize != size) {
      throw new DeliveryException(remote + ": remote size " + remoteSize + ", expected " + size);
    }
    String remoteSha;
    try (InputStream in = sftp.read(remote)) {
      remoteSha = sha256(in);
    }
    if (!remoteSha.equals(sha256)) {
      throw new DeliveryException(remote + ": read-back SHA-256 does not match the staged file");
    }
  }

  /**
   * Moves the upload into place. With the OpenSSH posix-rename extension the overwrite is atomic.
   * Otherwise the previous delivery is moved to {@code .prev} and restored if the final rename
   * fails, and a {@code .prev} left by an interrupted attempt is put back before anything is
   * removed; the published name is briefly absent in that fallback.
   */
  static void replace(SftpClient sftp, String partial, String remote) throws IOException {
    OpenSSHPosixRenameExtension posix = sftp.getExtension(OpenSSHPosixRenameExtension.class);
    if (posix != null && posix.isSupported()) {
      posix.posixRename(partial, remote);
      return;
    }
    String previous = remote + ".prev";
    if (!exists(sftp, remote) && exists(sftp, previous)) {
      sftp.rename(previous, remote);
    }
    removeIfPresent(sftp, previous);
    boolean hadPrevious = renameIfPresent(sftp, remote, previous);
    try {
      sftp.rename(partial, remote);
    } catch (IOException e) {
      if (hadPrevious) {
        try {
          sftp.rename(previous, remote);
        } catch (IOException restore) {
          e.addSuppressed(restore);
        }
      }
      throw e;
    }
    if (hadPrevious) {
      removeIfPresent(sftp, previous);
    }
  }

  private static boolean exists(SftpClient sftp, String path) throws IOException {
    try {
      sftp.lstat(path);
      return true;
    } catch (SftpException e) {
      if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
        throw e;
      }
      return false;
    }
  }

  private static boolean renameIfPresent(SftpClient sftp, String from, String to)
      throws IOException {
    try {
      sftp.rename(from, to);
      return true;
    } catch (SftpException e) {
      if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
        throw e;
      }
      return false;
    }
  }

  private static void removeIfPresent(SftpClient sftp, String remote) throws IOException {
    try {
      sftp.remove(remote);
    } catch (SftpException e) {
      if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
        throw e;
      }
    }
  }

  private static Iterable<KeyPair> loadKeys(SftpDestination d)
      throws IOException, GeneralSecurityException {
    FilePasswordProvider passphrase =
        d.privateKeyPassphraseFile() == null
            ? FilePasswordProvider.EMPTY
            : FilePasswordProvider.of(
                Files.readString(d.privateKeyPassphraseFile(), StandardCharsets.UTF_8).strip());
    try (InputStream in = Files.newInputStream(d.privateKeyFile())) {
      Iterable<KeyPair> keys =
          SecurityUtils.loadKeyPairIdentities(
              null, NamedResource.ofName("private-key-file"), in, passphrase);
      if (keys == null || !keys.iterator().hasNext()) {
        throw new DeliveryException("private-key-file holds no usable key");
      }
      return keys;
    }
  }

  static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  static String sha256(InputStream in) throws IOException {
    MessageDigest sha = digest();
    byte[] buffer = new byte[64 * 1024];
    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
      sha.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(sha.digest());
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
