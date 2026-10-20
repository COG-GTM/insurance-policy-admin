package com.acme.insurance.pas.batch.extract;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Writes one extract file record by record. Each record must be exactly {@code lrecl} bytes and is
 * terminated with CRLF (the z/OS FTP ASCII-mode rendering of an FB dataset). Output goes to a
 * per-writer {@code <name>.<random>.part} file that atomically replaces the target on {@link
 * #commit()}, so a rerun for the same business date overwrites the earlier file and a failed run
 * never leaves a partial one (Q-32).
 */
public final class ExtractFileWriter implements AutoCloseable {

  static final byte[] RECORD_TERMINATOR = {'\r', '\n'};

  private final Path target;
  private final Path part;
  private final int lrecl;
  private final DigestOutputStream out;
  private long records;
  private long bytes;
  private boolean committed;

  public ExtractFileWriter(Path target, int lrecl) {
    this.target = target;
    this.lrecl = lrecl;
    Path parent =
        Objects.requireNonNull(target.toAbsolutePath().getParent(), "target needs a directory");
    try {
      Files.createDirectories(parent);
      this.part = Files.createTempFile(parent, target.getFileName() + ".", ".part");
      OutputStream file = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16);
      this.out = new DigestOutputStream(file, MessageDigest.getInstance("SHA-256"));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot open a staging file for " + target, e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void write(byte[] record) {
    if (record.length != lrecl) {
      throw new IllegalArgumentException(
          "record length " + record.length + " != LRECL " + lrecl + " for " + target);
    }
    try {
      out.write(record);
      out.write(RECORD_TERMINATOR);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    records++;
    bytes += record.length + RECORD_TERMINATOR.length;
  }

  public ExtractFile commit() {
    try {
      out.close();
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot publish " + target, e);
    }
    committed = true;
    return new ExtractFile(
        target, records, bytes, HexFormat.of().formatHex(out.getMessageDigest().digest()));
  }

  @Override
  public void close() {
    if (committed) {
      return;
    }
    try {
      out.close();
      Files.deleteIfExists(part);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
