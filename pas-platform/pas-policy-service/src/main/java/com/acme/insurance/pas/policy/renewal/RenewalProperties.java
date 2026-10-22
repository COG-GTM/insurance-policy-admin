package com.acme.insurance.pas.policy.renewal;

import com.acme.insurance.pas.lifecycle.RenewalSettings;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pas.renewal.*}. {@code max-increase-pct} is the flat legacy cap (Q-04); {@code
 * max-increase-pct-by-state.<ST>} overrides it per policyholder state.
 */
@ConfigurationProperties(prefix = "pas.renewal")
public class RenewalProperties {
  private BigDecimal upliftPct = RenewalSettings.LEGACY_UPLIFT_PCT;
  private BigDecimal maxIncreasePct = RenewalSettings.LEGACY_CAP_PCT;
  private Map<String, BigDecimal> maxIncreasePctByState = new LinkedHashMap<>();
  private int maxOpenClaims = RenewalSettings.DEFAULT_MAX_OPEN_CLAIMS;
  private Integer windowDaysBeforeExpiry = 90;
  private Integer windowDaysAfterExpiry = 30;

  public RenewalSettings toSettings() {
    return new RenewalSettings(
        upliftPct,
        maxIncreasePct,
        maxIncreasePctByState,
        maxOpenClaims,
        windowDaysBeforeExpiry,
        windowDaysAfterExpiry);
  }

  public BigDecimal getUpliftPct() {
    return upliftPct;
  }

  public void setUpliftPct(BigDecimal upliftPct) {
    this.upliftPct = upliftPct;
  }

  public BigDecimal getMaxIncreasePct() {
    return maxIncreasePct;
  }

  public void setMaxIncreasePct(BigDecimal maxIncreasePct) {
    this.maxIncreasePct = maxIncreasePct;
  }

  public Map<String, BigDecimal> getMaxIncreasePctByState() {
    return Map.copyOf(maxIncreasePctByState);
  }

  public void setMaxIncreasePctByState(Map<String, BigDecimal> maxIncreasePctByState) {
    this.maxIncreasePctByState =
        maxIncreasePctByState == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(maxIncreasePctByState);
  }

  public int getMaxOpenClaims() {
    return maxOpenClaims;
  }

  public void setMaxOpenClaims(int maxOpenClaims) {
    this.maxOpenClaims = maxOpenClaims;
  }

  public Integer getWindowDaysBeforeExpiry() {
    return windowDaysBeforeExpiry;
  }

  public void setWindowDaysBeforeExpiry(Integer windowDaysBeforeExpiry) {
    this.windowDaysBeforeExpiry = windowDaysBeforeExpiry;
  }

  public Integer getWindowDaysAfterExpiry() {
    return windowDaysAfterExpiry;
  }

  public void setWindowDaysAfterExpiry(Integer windowDaysAfterExpiry) {
    this.windowDaysAfterExpiry = windowDaysAfterExpiry;
  }
}
