package com.acme.insurance.pas.policy.platform;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

final class PolicyJwtTestSupport {
  static final KeyPair KEY_PAIR = generateKeyPair();
  static final Path PUBLIC_KEY_FILE = writePublicKey();

  private PolicyJwtTestSupport() {}

  static String mint(String scope) throws JOSEException {
    return mint(scope, "policy-test-agent", Map.of());
  }

  static String mint(String scope, Map<String, Object> claims) throws JOSEException {
    return mint(scope, "policy-test-agent", claims);
  }

  static String mint(String scope, String subject, Map<String, Object> claims)
      throws JOSEException {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claimsBuilder =
        new JWTClaimsSet.Builder()
            .issuer("pas-it")
            .claim("scope", scope)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)));
    if (subject != null) {
      claimsBuilder.subject(subject);
    }
    claims.forEach(claimsBuilder::claim);
    JWTClaimsSet jwtClaims = claimsBuilder.build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID("pas-it")
                .build(),
            jwtClaims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
    return jwt.serialize();
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static Path writePublicKey() {
    try {
      String encoded =
          Base64.getMimeEncoder(64, new byte[] {'\n'})
              .encodeToString(KEY_PAIR.getPublic().getEncoded());
      Path path = Files.createTempFile("pas-policy-public-", ".pem");
      Files.writeString(
          path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
      return path;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
