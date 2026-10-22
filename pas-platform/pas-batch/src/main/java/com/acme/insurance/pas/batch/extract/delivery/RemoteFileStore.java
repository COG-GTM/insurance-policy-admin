package com.acme.insurance.pas.batch.extract.delivery;

import java.io.IOException;
import java.nio.file.Path;

public interface RemoteFileStore {
  Session connect(SftpProperties.Target target) throws IOException;

  interface Session extends AutoCloseable {
    void delete(String remotePath) throws IOException;

    void upload(Path localFile, String remotePath) throws IOException;

    long size(String remotePath) throws IOException;

    void rename(String source, String destination) throws IOException;

    void write(byte[] content, String remotePath) throws IOException;

    @Override
    void close() throws IOException;
  }
}
