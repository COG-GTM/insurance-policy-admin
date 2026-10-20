package com.acme.insurance.pas.batch.extract;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local extract output. Files land in {@code <outputDir>/<jobName>/<businessDate>/<file>} before
 * delivery; the directory is kept so operators can inspect or re-deliver a run.
 */
@ConfigurationProperties("pas.extract")
public record ExtractProperties(Path outputDir) {

  public ExtractProperties {
    if (outputDir == null) {
      outputDir = Path.of("build", "extracts");
    }
  }
}
