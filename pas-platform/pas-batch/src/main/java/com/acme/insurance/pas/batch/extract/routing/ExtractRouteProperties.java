package com.acme.insurance.pas.batch.extract.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pas.routing")
public record ExtractRouteProperties(RouteMode dailyExtract, RouteMode monthlyExposure) {
  // coordinator to reconcile with pas-common RouteFlags (AB-524)
  public ExtractRouteProperties {
    dailyExtract = dailyExtract == null ? RouteMode.LEGACY : dailyExtract;
    monthlyExposure = monthlyExposure == null ? RouteMode.LEGACY : monthlyExposure;
  }

  public RouteMode forJob(String jobName) {
    return switch (jobName) {
      case "dailyExtractJob" -> dailyExtract;
      case "monthlyExposureJob" -> monthlyExposure;
      default -> throw new IllegalArgumentException("Unknown extract job");
    };
  }
}
