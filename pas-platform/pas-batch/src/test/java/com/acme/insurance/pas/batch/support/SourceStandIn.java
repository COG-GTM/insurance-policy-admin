package com.acme.insurance.pas.batch.support;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;

/**
 * AB-529 DB2 stand-in: an in-memory H2 database in DB2 mode with the java-facade ACMEINS schema and
 * seed (read-only use of the legacy files) plus {@code pas-platform/dev/migration/
 * acmeins-extra.sql} for PREMIUMS, ENDORSEMENTS and UNDERWRITING_DECISIONS.
 */
public final class SourceStandIn {

  private SourceStandIn() {}

  public static String url(String name) {
    return "jdbc:h2:mem:" + name + ";MODE=DB2;DB_CLOSE_DELAY=-1";
  }

  public static void seed(String url) throws SQLException {
    Path root = ParityFixtures.repoRoot();
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        Statement st = c.createStatement()) {
      for (String file :
          new String[] {
            "java-facade/src/main/resources/schema.sql",
            "java-facade/src/main/resources/data.sql",
            "pas-platform/dev/migration/acmeins-extra.sql"
          }) {
        st.execute("RUNSCRIPT FROM '" + root.resolve(file).toString().replace('\\', '/') + "'");
      }
    }
  }

  public static int execute(String url, String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(url, "sa", "");
        Statement st = c.createStatement()) {
      return st.executeUpdate(sql);
    }
  }

  /** Random Base64 256-bit key for tests (never a real key). */
  public static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
