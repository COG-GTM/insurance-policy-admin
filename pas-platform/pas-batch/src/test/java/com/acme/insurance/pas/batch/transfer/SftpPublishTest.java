package com.acme.insurance.pas.batch.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
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

    renamesBeforeFailure = Integer.MAX_VALUE; // retry: the stranded backup is put back first
    SftpFileTransfer.reconcileBackups(sftp, List.of("out.dat"));
    assertThat(remoteFiles).containsEntry("out.dat", "old").doesNotContainKey("out.dat.prev");

    renamesBeforeFailure = 1; // and a failing retry still leaves it in place
    assertThatThrownBy(() -> SftpFileTransfer.publish(sftp, "out.dat.part", "out.dat"))
        .isInstanceOf(IOException.class);
    renamesBeforeFailure = Integer.MAX_VALUE;
    SftpFileTransfer.reconcileBackups(sftp, List.of("out.dat"));
    assertThat(remoteFiles).containsEntry("out.dat", "old");
  }

  @Test
  void unconfirmedPublicationIsRolledBackToLastConfirmedDelivery() throws IOException {
    // new bytes published, then the connection dropped before the sidecar and .done markers
    remoteFiles.put("a.dat", "new-a");
    remoteFiles.put("a.dat.prev", "old-a");
    remoteFiles.put("a.dat.sha256", "sha-of-new-a");
    remoteFiles.put("a.dat.done", "partial set");
    remoteFiles.put("b.dat", "new-b");
    remoteFiles.put("b.dat.prev", "old-b");

    SftpFileTransfer.reconcileBackups(sftp, List.of("a.dat", "b.dat"));

    assertThat(remoteFiles)
        .containsEntry("a.dat", "old-a")
        .containsEntry("b.dat", "old-b")
        .doesNotContainKeys("a.dat.prev", "b.dat.prev", "a.dat.sha256");
  }

  @Test
  void confirmedDeliveryDropsLeftoverBackups() throws IOException {
    remoteFiles.put("a.dat", "new-a");
    remoteFiles.put("a.dat.done", "ok");
    remoteFiles.put("a.dat.prev", "old-a");

    SftpFileTransfer.reconcileBackups(sftp, List.of("a.dat"));

    assertThat(remoteFiles).containsOnlyKeys("a.dat", "a.dat.done").containsEntry("a.dat", "new-a");
  }
}
