package com.acme.insurance.pas.batch.extract.delivery;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pas.batch.sftp")
public class SftpProperties {
  private Map<String, Target> targets = new LinkedHashMap<>();

  public Map<String, Target> getTargets() {
    return Map.copyOf(targets);
  }

  public void setTargets(Map<String, Target> targets) {
    this.targets = targets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
  }

  public record Target(
      String host,
      int port,
      String username,
      String privateKeyFile,
      String keyPassphraseFile,
      String knownHostsFile,
      String remoteDir,
      Duration connectTimeout) {}
}
