package com.acme.insurance.pas.policyservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A throwaway token issuer for tests: an RSA key pair generated per JVM, the public half written to
 * a temporary PEM file the service is configured with, so tokens go through the real validation.
 */
public final class TestJwt {

  public static final String ISSUER = "https://idp.test.invalid/pas";
  public static final String AUDIENCE = "pas-policy-service";

  private static final KeyPair KEYS = generate();
  private static final KeyPair OTHER_KEYS = generate();
  private static final Path PUBLIC_KEY = writePublicKey();

  private TestJwt() {}

  public static void register(DynamicPropertyRegistry registry) {
    registry.add("pas.security.jwt.issuer", () -> ISSUER);
    registry.add("pas.security.jwt.audience", () -> AUDIENCE);
    registry.add("pas.security.jwt.public-key-location", () -> PUBLIC_KEY.toUri().toString());
  }

  /** Claims for a valid token: right issuer and audience, expiring in five minutes. */
  public static JWTClaimsSet.Builder claims(String scope) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject("test-user")
        .claim("azp", "test-client")
        .claim("scope", scope)
        .jwtID(UUID.randomUUID().toString())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
  }

  public static String sign(JWTClaimsSet claims) {
    return sign(claims, KEYS.getPrivate());
  }

  /** Signed with a key the service does not trust. */
  public static String signWithUntrustedKey(JWTClaimsSet claims) {
    return sign(claims, OTHER_KEYS.getPrivate());
  }

  /** A whole-book token with the given scope. */
  public static String wholeBook(String scope) {
    return sign(claims(scope).claim("pas_book", "*").build());
  }

  public static String agents(String scope, String... agentCodes) {
    return sign(claims(scope).claim("pas_agents", List.of(agentCodes)).build());
  }

  public static String branches(String scope, String... branchCodes) {
    return sign(claims(scope).claim("pas_branches", List.of(branchCodes)).build());
  }

  public static Map<String, String> bearer(String jwt) {
    return Map.of("Authorization", "Bearer " + jwt);
  }

  private static String sign(JWTClaimsSet claims, PrivateKey key) {
    try {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
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
      String body =
          Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded());
      Files.writeString(
          file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
