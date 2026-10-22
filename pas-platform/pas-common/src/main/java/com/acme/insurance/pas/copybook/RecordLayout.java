package com.acme.insurance.pas.copybook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecordLayout {
  private final String name;
  private final List<FieldDescriptor> fields;
  private final Map<String, FieldDescriptor> byName;
  private final int length;

  private RecordLayout(String name, List<FieldDescriptor> fields, int length) {
    this.name = name;
    this.fields = List.copyOf(fields);
    Map<String, FieldDescriptor> index = new LinkedHashMap<>();
    for (FieldDescriptor field : fields) {
      if (index.put(field.name(), field) != null) {
        throw new IllegalArgumentException("Duplicate field name");
      }
    }
    this.byName = Collections.unmodifiableMap(index);
    this.length = length;
  }

  public String name() {
    return name;
  }

  public List<FieldDescriptor> fields() {
    return fields;
  }

  public FieldDescriptor field(String name) {
    FieldDescriptor field = byName.get(name);
    if (field == null) {
      throw new IllegalArgumentException("Unknown field name");
    }
    return field;
  }

  public int length() {
    return length;
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static final class Builder {
    private final String name;
    private final List<FieldDescriptor> fields = new ArrayList<>();
    private int offset;

    private Builder(String name) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Record name is required");
      }
      this.name = name;
    }

    public Builder x(String fieldName, int length) {
      return add(fieldName, new FieldType.Alphanumeric(length));
    }

    public Builder display(String fieldName, int digits) {
      return add(fieldName, new FieldType.UnsignedDisplay(digits, 0));
    }

    public Builder display(String fieldName, int digits, int scale) {
      return add(fieldName, new FieldType.UnsignedDisplay(digits, scale));
    }

    public Builder signedDisplay(String fieldName, int digits, int scale) {
      return add(fieldName, new FieldType.SignedDisplay(digits, scale));
    }

    public Builder packed(String fieldName, int digits, int scale, boolean signed) {
      return add(fieldName, new FieldType.PackedDecimal(digits, scale, signed));
    }

    public Builder date(String fieldName) {
      return add(fieldName, new FieldType.DateYyyyMmDd());
    }

    public Builder filler(int length) {
      return add("FILLER-" + fields.size(), new FieldType.Filler(length));
    }

    public Builder add(String fieldName, FieldType type) {
      fields.add(new FieldDescriptor(fieldName, offset, type));
      offset = Math.addExact(offset, type.byteLength());
      return this;
    }

    public RecordLayout build(int expectedLength) {
      if (offset != expectedLength) {
        throw new IllegalArgumentException("Record length does not match expected length");
      }
      return new RecordLayout(name, fields, offset);
    }
  }
}
