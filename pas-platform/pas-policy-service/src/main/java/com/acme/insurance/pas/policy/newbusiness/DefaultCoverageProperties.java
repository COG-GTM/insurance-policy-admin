package com.acme.insurance.pas.policy.newbusiness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas.new-business")
public record DefaultCoverageProperties(Map<String, List<DefaultCoverage>> defaultCoverages) {
  public DefaultCoverageProperties {
    defaultCoverages =
        defaultCoverages == null
            ? Map.of()
            : defaultCoverages.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The canonical constructor deep-copies the map and all nested lists.")
  @Override
  public Map<String, List<DefaultCoverage>> defaultCoverages() {
    return defaultCoverages;
  }

  public record DefaultCoverage(String type, String description) {}
}
