package com.acme.insurance.pas.batch.extract;

import java.nio.file.Path;

/** A completed local extract file: its path, record count, byte size and SHA-256 (hex). */
public record ExtractFile(Path path, long records, long bytes, String sha256) {}
