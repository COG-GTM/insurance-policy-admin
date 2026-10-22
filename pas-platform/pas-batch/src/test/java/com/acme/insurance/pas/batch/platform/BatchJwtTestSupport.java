package com.acme.insurance.pas.batch.platform;

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

final class BatchJwtTestSupport {
  static final KeyPair KEY_PAIR = generateKeyPair();
  static final Path PUBLIC_KEY_FILE = writePublicKey();

  private BatchJwtTestSupport() {}

  static String mint(String scope) throws JOSEException {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("pas-it")
            .subject("batch-test-agent")
            .claim("scope", scope)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID("pas-it")
                .build(),
            claims);
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
      Path path = Files.createTempFile("pas-batch-public-", ".pem");
      Files.writeString(
          path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
      return path;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
