package com.acme.insurance.pas.batch.extract;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One SFTP endpoint. Only the {@code sftp} scheme is accepted, authentication is by private key
 * only, and the server must be listed in the known-hosts file (no trust-on-first-use).
 *
 * @param uri {@code sftp://host[:port]/remote/dir}
 * @param privateKeyFile private key mounted from the secrets manager
 * @param privateKeyPassphraseFile optional file holding the key passphrase
 * @param knownHostsFile OpenSSH known_hosts with the pinned host key(s) of the endpoint
 */
public record SftpDestination(
    URI uri,
    String username,
    Path privateKeyFile,
    Path privateKeyPassphraseFile,
    Path knownHostsFile,
    @DefaultValue("PT30S") Duration timeout) {

  static final int DEFAULT_PORT = 22;

  public boolean configured() {
    return uri != null && !uri.toString().isBlank();
  }

  public String host() {
    return uri.getHost();
  }

  public int port() {
    return uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
  }

  public String remoteDir() {
    String path = uri.getPath();
    return path == null || path.isEmpty() ? "." : path;
  }

  /** Where a file lands, without credentials: used in receipts and logs. */
  public String describe(String remoteName) {
    return "sftp://" + host() + ":" + port() + remotePath(remoteName);
  }

  public String remotePath(String remoteName) {
    String dir = remoteDir();
    return dir.endsWith("/") ? dir + remoteName : dir + "/" + remoteName;
  }

  /** Problems that stop the job before any data is extracted; empty when the endpoint is usable. */
  public List<String> problems(String name) {
    List<String> problems = new ArrayList<>();
    if (!configured()) {
      problems.add(name + ": uri is not set");
      return problems;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"sftp".equals(scheme)) {
      problems.add(name + ": only sftp is allowed, got scheme '" + scheme + "'");
    }
    if (uri.getUserInfo() != null) {
      problems.add(name + ": the uri must not carry user info; set username and key separately");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      problems.add(name + ": uri has no host");
    }
    if (username == null || username.isBlank()) {
      problems.add(name + ": username is not set");
    }
    requireReadable(problems, name + ": private-key-file", privateKeyFile);
    requireReadable(problems, name + ": known-hosts-file", knownHostsFile);
    if (privateKeyPassphraseFile != null) {
      requireReadable(problems, name + ": private-key-passphrase-file", privateKeyPassphraseFile);
    }
    return problems;
  }

  private static void requireReadable(List<String> problems, String what, Path file) {
    if (file == null || file.toString().isBlank()) {
      problems.add(what + " is not set");
    } else if (!Files.isReadable(file)) {
      problems.add(what + " is not readable");
    }
  }
}
