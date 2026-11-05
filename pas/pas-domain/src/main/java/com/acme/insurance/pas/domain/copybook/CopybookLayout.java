package com.acme.insurance.pas.domain.copybook;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Ordered elementary items of a 01-level record, with their offsets. */
public final class CopybookLayout {

  private final String name;
  private final List<CopybookField> fields;
  private final List<Integer> offsets;
  private final int length;

  public CopybookLayout(String name, List<CopybookField> fields) {
    this.name = name;
    this.fields = List.copyOf(fields);
    List<Integer> starts = new ArrayList<>(fields.size());
    int offset = 0;
    for (CopybookField field : this.fields) {
      starts.add(offset);
      offset += field.length();
    }
    this.offsets = List.copyOf(starts);
    this.length = offset;
  }

  public String name() {
    return name;
  }

  public List<CopybookField> fields() {
    return fields;
  }

  /** Total record length in bytes. */
  public int length() {
    return length;
  }

  /** Zero-based offset of the item at {@code index} in {@link #fields()}. */
  public int offset(int index) {
    return offsets.get(index);
  }

  public RecordReader reader(byte[] record, Charset charset) {
    if (record.length != length) {
      throw new CopybookDataException(name + " must be " + length + " bytes, got " + record.length);
    }
    return new RecordReader(this, record, charset);
  }

  public RecordWriter writer(Charset charset) {
    return new RecordWriter(this, charset);
  }
}
