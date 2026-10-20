package com.acme.insurance.pas.batch.transfer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SFTP delivery (Apache MINA sshd). Per destination and attempt:
 *
 * <ol>
 *   <li>connect; the server host key must match {@code known_hosts} (unknown/changed keys are
 *       rejected); authenticate with the private key only (no password/keyboard-interactive);
 *   <li>for each file: remove a stale {@code <name>.done}, upload to {@code <name>.part}, verify
 *       the remote size and SHA-256 by reading it back, then rename to {@code <name>} and write the
 *       {@code <name>.sha256} sidecar ({@code sha256sum} format);
 *   <li>after every file is in place, write one {@code <name>.done} marker per file (consumers pick
 *       up a file only once its marker exists).
 * </ol>
 *
 * Any failure retries the whole delivery with exponential backoff; reruns are idempotent.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "shares Spring-managed collaborators (MeterRegistry, JDBC, properties) by design")
public class SftpFileTransfer implements SecureFileTransfer {

  private static final Logger LOG = LoggerFactory.getLogger(SftpFileTransfer.class);

  /** Sleeps between attempts; replaceable in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  public static final Sleeper THREAD_SLEEP = d -> Thread.sleep(d.toMillis());

  private final TransferProperties properties;
  private final MeterRegistry meters;
  private final Sleeper sleeper;

  public SftpFileTransfer(TransferProperties properties, MeterRegistry meters, Sleeper sleeper) {
    this.properties = properties;
    this.meters = meters;
    this.sleeper = sleeper;
  }

  @Override
  public List<DeliveryReceipt> deliver(String destinationName, List<DeliveryFile> files) {
    TransferProperties.Destination destination = properties.destination(destinationName);
    TransferProperties.Retry retry = properties.retry();
    List<LocalFile> locals = new ArrayList<>();
    for (DeliveryFile file : files) {
      locals.add(LocalFile.of(file));
    }
    Exception last = null;
    for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
      if (attempt > 1) {
        Duration backoff = retry.backoffBefore(attempt);
        LOG.warn(
            "retrying delivery to {} in {} (attempt {}/{})",
            destinationName,
            backoff,
            attempt,
            retry.maxAttempts());
        try {
          sleeper.sleep(backoff);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new FileDeliveryException("interrupted delivering to " + destinationName, e);
        }
      }
      Timer.Sample sample = Timer.start(meters);
      try {
        List<DeliveryReceipt> receipts = deliverOnce(destinationName, destination, locals);
        sample.stop(meters.timer("pas.transfer.duration", "destination", destinationName));
        meters
            .counter(
                "pas.transfer.deliveries", "destination", destinationName, "outcome", "success")
            .increment();
        int succeededAttempt = attempt;
        receipts.forEach(
            r ->
                LOG.info(
                    "delivered destination={} remote={} bytes={} sha256={} attempt={}",
                    destinationName,
                    r.remotePath(),
                    r.bytes(),
                    r.sha256(),
                    succeededAttempt));
        return receipts;
      } catch (IOException | GeneralSecurityException | RuntimeException e) {
        last = e;
        meters.counter("pas.transfer.attempt.failures", "destination", destinationName).increment();
        LOG.warn(
            "delivery attempt {}/{} to {} failed: {}",
            attempt,
            retry.maxAttempts(),
            destinationName,
            e.toString());
      }
    }
    meters
        .counter("pas.transfer.deliveries", "destination", destinationName, "outcome", "failure")
        .increment();
    throw new FileDeliveryException(
        "delivery to "
            + destinationName
            + " failed after "
            + retry.maxAttempts()
            + " attempt(s): "
            + last,
        last);
  }

  private List<DeliveryReceipt> deliverOnce(
      String name, TransferProperties.Destination d, List<LocalFile> files)
      throws IOException, GeneralSecurityException {
    SshClient client = SshClient.setUpDefaultClient();
    client.setServerKeyVerifier(
        new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, d.knownHosts()));
    client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
    client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
    client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
    client.start();
    try (ClientSession session =
        client
            .connect(d.username(), d.host(), d.port())
            .verify(properties.connectTimeout())
            .getSession()) {
      for (KeyPair key : loadKeys(d)) {
        session.addPublicKeyIdentity(key);
      }
      session.auth().verify(properties.authTimeout());
      try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
        String dir =
            d.remoteDirectory().endsWith("/") ? d.remoteDirectory() : d.remoteDirectory() + "/";
        List<DeliveryReceipt> receipts = new ArrayList<>();
        for (LocalFile file : files) {
          String remote = dir + file.remoteName();
          removeIfExists(sftp, remote + ".done");
          String part = remote + ".part";
          try (OutputStream out = sftp.write(part)) {
            Files.copy(file.path(), out);
          }
          verify(sftp, part, file);
          removeIfExists(sftp, remote);
          sftp.rename(part, remote);
          writeText(sftp, remote + ".sha256", file.sha256() + "  " + file.remoteName() + "\n");
          receipts.add(new DeliveryReceipt(name, remote, file.bytes(), file.sha256()));
        }
        for (LocalFile file : files) {
          writeText(
              sftp,
              dir + file.remoteName() + ".done",
              "file="
                  + file.remoteName()
                  + "\nbytes="
                  + file.bytes()
                  + "\nsha256="
                  + file.sha256()
                  + "\n");
        }
        return receipts;
      }
    } finally {
      client.stop();
    }
  }

  private static Iterable<KeyPair> loadKeys(TransferProperties.Destination d)
      throws IOException, GeneralSecurityException {
    FilePasswordProvider passphrase =
        d.privateKeyPassphrase() == null || d.privateKeyPassphrase().isEmpty()
            ? FilePasswordProvider.EMPTY
            : FilePasswordProvider.of(d.privateKeyPassphrase());
    try (InputStream in = Files.newInputStream(d.privateKey())) {
      Iterable<KeyPair> keys =
          SecurityUtils.loadKeyPairIdentities(
              null, NamedResource.ofName(d.privateKey().toString()), in, passphrase);
      if (keys == null || !keys.iterator().hasNext()) {
        throw new GeneralSecurityException("no usable key in " + d.privateKey());
      }
      return keys;
    }
  }

  private static void verify(SftpClient sftp, String remote, LocalFile file) throws IOException {
    long size = sftp.stat(remote).getSize();
    if (size != file.bytes()) {
      throw new IOException(
          "remote size mismatch for " + remote + ": " + size + " != " + file.bytes());
    }
    String remoteSha;
    try (InputStream in = sftp.read(remote)) {
      remoteSha = sha256(in);
    }
    if (!remoteSha.equals(file.sha256())) {
      throw new IOException("remote SHA-256 mismatch for " + remote);
    }
  }

  private static void writeText(SftpClient sftp, String remote, String text) throws IOException {
    try (OutputStream out = sftp.write(remote)) {
      out.write(text.getBytes(StandardCharsets.US_ASCII));
    }
  }

  private static void removeIfExists(SftpClient sftp, String remote) throws IOException {
    try {
      sftp.remove(remote);
    } catch (SftpException e) {
      if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
        throw e;
      }
    }
  }

  static String sha256(InputStream in) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (DigestInputStream din = new DigestInputStream(in, digest)) {
        din.transferTo(OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record LocalFile(Path path, String remoteName, long bytes, String sha256) {
    static LocalFile of(DeliveryFile file) {
      try (InputStream in = Files.newInputStream(file.localPath())) {
        return new LocalFile(
            file.localPath(),
            file.remoteName(),
            Files.size(file.localPath()),
            SftpFileTransfer.sha256(in));
      } catch (IOException e) {
        throw new FileDeliveryException("cannot read local file " + file.localPath(), e);
      }
    }
  }
}
