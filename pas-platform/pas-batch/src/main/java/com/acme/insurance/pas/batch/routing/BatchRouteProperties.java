package com.acme.insurance.pas.batch.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pas")
public class BatchRouteProperties {
  private Map<String, String> routing = new LinkedHashMap<>();

  public Map<String, String> getRouting() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(routing));
  }

  public void setRouting(Map<String, String> routing) {
    this.routing = routing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(routing);
  }
}
