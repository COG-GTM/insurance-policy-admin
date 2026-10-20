package com.acme.insurance.pas.policy.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * JWT validation per ADR 0001 D5: signature (JWKS), {@code iss}, {@code aud}, {@code exp}/{@code
 * nbf} and allowed algorithms. Keys come from, in order: {@code jwk-set-uri}; {@code
 * public-key-location} (local profile and tests only); otherwise OIDC discovery on {@code
 * issuer-uri}, resolved lazily so the service starts while the IdP is unreachable.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
public class JwtDecoderConfig {

  @Bean
  JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
    OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
    String issuer = jwt.getIssuerUri();
    if (!StringUtils.hasText(issuer)) {
      throw new IllegalStateException("PAS_OIDC_ISSUER_URI must be set");
    }
    List<String> audiences =
        jwt.getAudiences().stream().filter(StringUtils::hasText).map(String::strip).toList();
    if (audiences.isEmpty()) {
      throw new IllegalStateException("PAS_OIDC_AUDIENCE must be set");
    }
    OAuth2TokenValidator<Jwt> validator = validator(issuer, audiences);
    List<SignatureAlgorithm> algorithms = algorithms(jwt.getJwsAlgorithms());

    if (StringUtils.hasText(jwt.getJwkSetUri())) {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
              .jwsAlgorithms(a -> a.addAll(algorithms))
              .build();
      decoder.setJwtValidator(validator);
      return decoder;
    }
    Resource publicKey = jwt.getPublicKeyLocation();
    if (publicKey != null) {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withPublicKey(readPublicKey(publicKey))
              .signatureAlgorithm(algorithms.get(0))
              .build();
      decoder.setJwtValidator(validator);
      return decoder;
    }
    return new SupplierJwtDecoder(
        () -> {
          NimbusJwtDecoder decoder =
              NimbusJwtDecoder.withIssuerLocation(issuer)
                  .jwsAlgorithms(a -> a.addAll(algorithms))
                  .build();
          decoder.setJwtValidator(validator);
          return decoder;
        });
  }

  static OAuth2TokenValidator<Jwt> validator(String issuer, List<String> audiences) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(issuer));
    validators.add(
        new JwtClaimValidator<List<String>>(
            JwtClaimNames.AUD, aud -> aud != null && aud.stream().anyMatch(audiences::contains)));
    return new DelegatingOAuth2TokenValidator<>(validators);
  }

  private static List<SignatureAlgorithm> algorithms(List<String> names) {
    List<SignatureAlgorithm> algorithms = new ArrayList<>();
    for (String name : names) {
      SignatureAlgorithm algorithm = SignatureAlgorithm.from(name.strip());
      if (algorithm == null) {
        throw new IllegalStateException("Unsupported JWS algorithm: " + name);
      }
      algorithms.add(algorithm);
    }
    if (algorithms.isEmpty()) {
      algorithms.add(SignatureAlgorithm.RS256);
    }
    return algorithms;
  }

  private static RSAPublicKey readPublicKey(Resource location) {
    try (InputStream in = location.getInputStream()) {
      String pem =
          new String(in.readAllBytes(), StandardCharsets.US_ASCII)
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replaceAll("\\s", "");
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Cannot read JWT public key from " + location, e);
    }
  }
}
