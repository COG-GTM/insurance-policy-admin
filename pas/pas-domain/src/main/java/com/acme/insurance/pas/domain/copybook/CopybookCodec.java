package com.acme.insurance.pas.domain.copybook;

import java.nio.charset.Charset;

/**
 * Converts between a domain record and its fixed-width copybook bytes.
 *
 * <p>Round trip guarantee: {@code encode(decode(bytes))} returns the same bytes for any record
 * whose fillers are spaces and whose packed fields use the preferred C/D signs. {@code
 * decode(encode(value))} returns an equal value except where the copybook cannot carry the data:
 * blank text and null text both read as null, null numbers read as 0, and TIMESTAMP columns keep
 * only the date (Q-521-1).
 */
public abstract class CopybookCodec<T> {

  /** EBCDIC code page 037, used by the z/OS datasets and MQ messages. */
  public static final Charset EBCDIC = Charset.forName("IBM037");

  private final CopybookLayout layout;
  private final Charset charset;

  protected CopybookCodec(CopybookLayout layout, Charset charset) {
    this.layout = layout;
    this.charset = charset;
  }

  public T decode(byte[] record) {
    RecordReader reader = layout.reader(record, charset);
    T value = read(reader);
    reader.end();
    return value;
  }

  public byte[] encode(T value) {
    RecordWriter writer = layout.writer(charset);
    write(value, writer);
    return writer.toBytes();
  }

  protected abstract T read(RecordReader in);

  protected abstract void write(T value, RecordWriter out);
}
