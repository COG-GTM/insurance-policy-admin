package com.acme.insurance.pas.policy.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Throwaway RSA issuer for tests: a fresh key pair per JVM, the public half written to a temp PEM
 * and wired in as {@code public-key-location}, so tests exercise real signature, issuer and
 * audience validation. Nothing is committed.
 */
public final class TestJwtIssuer {

  public static final String ISSUER = "https://pas-test-issuer.local";
  public static final String AUDIENCE = "pas-api";

  private static final KeyPair KEYS = generate();
  private static final Path PUBLIC_KEY = writePublicKey();

  private TestJwtIssuer() {}

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("PAS_OIDC_ISSUER_URI", () -> ISSUER);
    registry.add("PAS_OIDC_AUDIENCE", () -> AUDIENCE);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> PUBLIC_KEY.toUri().toString());
  }

  public static Builder bearer() {
    return new Builder();
  }

  /** Fluent builder for a signed access token. */
  public static final class Builder {
    private final JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("test-user")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));

    public Builder subject(String subject) {
      claims.subject(subject);
      return this;
    }

    public Builder scopes(String... scopes) {
      claims.claim("scope", String.join(" ", scopes));
      return this;
    }

    public Builder agent(String agentCode) {
      claims.claim(CallerEntitlements.CLAIM_AGENT_CODE, agentCode);
      return this;
    }

    public Builder branch(String branchCode) {
      claims.claim(CallerEntitlements.CLAIM_BRANCH_CODE, branchCode);
      return this;
    }

    public Builder book(String... entries) {
      claims.claim(CallerEntitlements.CLAIM_BOOK, List.of(entries));
      return this;
    }

    public Builder issuer(String issuer) {
      claims.issuer(issuer);
      return this;
    }

    public Builder audience(String audience) {
      claims.audience(audience);
      return this;
    }

    public Builder expiresAt(Instant instant) {
      claims.expirationTime(Date.from(instant));
      return this;
    }

    public Builder claims(Map<String, Object> extra) {
      extra.forEach(claims::claim);
      return this;
    }

    /** Signed compact JWT. */
    public String sign() {
      return sign((RSAPrivateKey) KEYS.getPrivate());
    }

    /** Signed with a different key than the service trusts. */
    public String signWithUntrustedKey() {
      return sign((RSAPrivateKey) generate().getPrivate());
    }

    private String sign(RSAPrivateKey key) {
      try {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path writePublicKey() {
    try {
      Path file = Files.createTempFile("pas-test-jwt", ".pem");
      file.toFile().deleteOnExit();
      String pem =
          "-----BEGIN PUBLIC KEY-----\n"
              + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                  .encodeToString(KEYS.getPublic().getEncoded())
              + "\n-----END PUBLIC KEY-----\n";
      Files.writeString(file, pem, StandardCharsets.US_ASCII);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
