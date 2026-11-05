package com.acme.insurance.pas.batch.extract;

import com.acme.insurance.pas.persistence.ExtractRow;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Writes one feed file to the staging directory. The file is written under a temporary name and
 * moved into place when complete, readable by the owner only, and its SHA-256 is computed on the
 * bytes as they are written.
 */
public class FeedFileWriter {

  private final Path stagingDir;
  private final Charset charset;
  private final String recordSeparator;

  public FeedFileWriter(Path stagingDir, Charset charset, String recordSeparator) {
    this.stagingDir = stagingDir;
    this.charset = charset;
    this.recordSeparator = recordSeparator;
  }

  public StagedFile write(
      ExtractFeed feed, LocalDate businessDate, Consumer<Consumer<ExtractRow>> rows) {
    FeedLayout layout = feed.layout();
    Path target = stagingDir.resolve(feed.stagingName(businessDate));
    Path partial = stagingDir.resolve(target.getFileName() + ".part");
    CharsetEncoder encoder =
        charset
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      createOwnerOnly(stagingDir, true);
      Files.deleteIfExists(partial);
      createOwnerOnly(partial, false);
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      long[] counts = new long[2];
      try (OutputStream file = Files.newOutputStream(partial);
          DigestOutputStream digest = new DigestOutputStream(new BufferedOutputStream(file), sha)) {
        if (layout.header() != null) {
          counts[1] += write(digest, encoder, layout.header() + recordSeparator, feed);
        }
        rows.accept(
            row -> {
              counts[1] += write(digest, encoder, layout.render(row) + recordSeparator, feed);
              counts[0]++;
            });
      }
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return new StagedFile(
          feed, businessDate, target, counts[0], counts[1], HexFormat.of().formatHex(sha.digest()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot stage " + feed.feedId(), e);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    } finally {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException ignored) {
        // the partial file is replaced on the next run
      }
    }
  }

  private static long write(
      OutputStream out, CharsetEncoder encoder, String text, ExtractFeed feed) {
    try {
      ByteBuffer bytes = encoder.reset().encode(CharBuffer.wrap(text));
      out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
      return bytes.remaining();
    } catch (CharacterCodingException e) {
      throw new FeedLayout.FeedFormatException(
          feed.feedId() + ": a value cannot be encoded in " + encoder.charset().name());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void createOwnerOnly(Path path, boolean directory) throws IOException {
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if (directory) {
      if (!Files.isDirectory(path)) {
        if (posix) {
          Files.createDirectories(
              path,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
          Files.createDirectories(path);
        }
      }
    } else if (posix) {
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(path);
    }
  }
}
