package com.acme.insurance.pas.rating.config;

import com.acme.insurance.pas.rating.FactorMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pas.rating")
public class RatingProperties {
  private FactorMode factorMode = FactorMode.LEGACY;

  public FactorMode getFactorMode() {
    return factorMode;
  }

  public void setFactorMode(FactorMode factorMode) {
    this.factorMode = factorMode;
  }
}
