package com.acme.insurance.pas.batch.rating;

import com.acme.insurance.pas.rating.RatingFactorMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pas.rating.*}. {@code factor-source} is {@code legacy-neutral} (default, every factor
 * 1.0000 as PREMBAT) or {@code table-driven} (territory and class factors from the reference
 * tables; only after Actuarial sign-off of FU-03). {@code cache-ttl} (default 5 minutes) bounds how
 * long a loaded rate table is reused before a new effective-dated row is seen.
 */
@ConfigurationProperties("pas.rating")
public record RatingProperties(
    @DefaultValue("LEGACY_NEUTRAL") RatingFactorMode factorSource,
    @DefaultValue("PT5M") Duration cacheTtl) {}
