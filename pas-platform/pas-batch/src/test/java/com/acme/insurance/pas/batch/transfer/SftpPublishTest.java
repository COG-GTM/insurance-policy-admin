package com.acme.insurance.pas.batch.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.Test;

class SftpPublishTest {

  /** In-memory remote directory whose renames can be made to fail. */
  private final Map<String, String> remoteFiles = new HashMap<>();

  private int renamesBeforeFailure = Integer.MAX_VALUE;

  private final SftpClient sftp =
      (SftpClient)
          Proxy.newProxyInstance(
              SftpClient.class.getClassLoader(),
              new Class<?>[] {SftpClient.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "stat" -> {
                      if (!remoteFiles.containsKey((String) args[0])) {
                        throw new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "no file");
                      }
                      yield new SftpClient.Attributes();
                    }
                    case "remove" -> {
                      if (remoteFiles.remove((String) args[0]) == null) {
                        throw new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "no file");
                      }
                      yield null;
                    }
                    case "rename" -> {
                      if (renamesBeforeFailure-- <= 0) {
                        throw new IOException("connection lost");
                      }
                      String content = remoteFiles.remove((String) args[0]);
                      if (content == null) {
                        throw new SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "no file");
                      }
                      remoteFiles.put((String) args[1], content);
                      yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                  });

  @Test
  void failedPublishThenFailedRetryKeepsLastGoodDelivery() throws IOException {
    remoteFiles.put("out.dat", "old");
    remoteFiles.put("out.dat.part", "new");
    renamesBeforeFailure = 1; // backup succeeds, publish and restore both fail

    assertThatThrownBy(() -> SftpFileTransfer.publish(sftp, "out.dat.part", "out.dat"))
        .isInstanceOf(IOException.class);
    assertThat(remoteFiles).containsEntry("out.dat.prev", "old").doesNotContainKey("out.dat");

    renamesBeforeFailure = 1; // retry: recovery succeeds, the next rename fails
    assertThatThrownBy(() -> SftpFileTransfer.publish(sftp, "out.dat.part", "out.dat"))
        .isInstanceOf(IOException.class);
    assertThat(
            remoteFiles.get("out.dat").equals("old")
                || "old".equals(remoteFiles.get("out.dat.prev")))
        .isTrue();

    renamesBeforeFailure = Integer.MAX_VALUE;
    SftpFileTransfer.publish(sftp, "out.dat.part", "out.dat");
    assertThat(remoteFiles)
        .containsEntry("out.dat", "new")
        .containsEntry("out.dat.prev", "old")
        .doesNotContainKey("out.dat.part");
  }

  @Test
  void supersededBackupIsDroppedWhenFinalFileExists() throws IOException {
    remoteFiles.put("out.dat", "verified");
    remoteFiles.put("out.dat.prev", "stale");

    SftpFileTransfer.recoverStrandedBackup(sftp, "out.dat", "out.dat.prev");

    assertThat(remoteFiles).containsOnlyKeys("out.dat").containsEntry("out.dat", "verified");
  }
}
