package com.acme.insurance.pas.batch.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractFileWriterTest {

  @TempDir Path dir;

  @Test
  void writesCrlfRecordsAndReplacesPreviousRunAtomically() throws Exception {
    Path target = dir.resolve("out.dat");
    Files.writeString(target, "old run");
    ExtractFile file;
    try (ExtractFileWriter writer = new ExtractFileWriter(target, 3)) {
      writer.write("abc".getBytes());
      writer.write("def".getBytes());
      file = writer.commit();
    }
    assertThat(Files.readString(target)).isEqualTo("abc\r\ndef\r\n");
    assertThat(file.records()).isEqualTo(2);
    assertThat(file.bytes()).isEqualTo(10);
    assertThat(file.sha256()).hasSize(64);
    try (var files = Files.list(dir)) {
      assertThat(files).extracting(f -> f.getFileName().toString()).containsExactly("out.dat");
    }
  }

  @Test
  void concurrentWritersUseSeparateStagingFiles() throws Exception {
    Path target = dir.resolve("out.dat");
    try (ExtractFileWriter first = new ExtractFileWriter(target, 3);
        ExtractFileWriter second = new ExtractFileWriter(target, 3)) {
      first.write("aaa".getBytes());
      second.write("bbb".getBytes());
      first.write("ccc".getBytes());
      first.commit();
      assertThat(Files.readString(target)).isEqualTo("aaa\r\nccc\r\n");
      second.commit();
    }
    assertThat(Files.readString(target)).isEqualTo("bbb\r\n");
  }

  @Test
  void failedRunLeavesPreviousFileAndNoPart() throws Exception {
    Path target = dir.resolve("out.dat");
    Files.writeString(target, "old run");
    try (ExtractFileWriter writer = new ExtractFileWriter(target, 3)) {
      assertThatThrownBy(() -> writer.write("toolong".getBytes()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(Files.readString(target)).isEqualTo("old run");
    assertThat(dir.resolve("out.dat.part")).doesNotExist();
  }
}
