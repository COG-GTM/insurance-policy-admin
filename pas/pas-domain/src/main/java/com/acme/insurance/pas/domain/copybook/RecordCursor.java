package com.acme.insurance.pas.domain.copybook;

/** Walks a layout in order so a codec cannot read or write a field out of sequence. */
abstract class RecordCursor {

  final CopybookLayout layout;
  private int index;

  RecordCursor(CopybookLayout layout) {
    this.layout = layout;
  }

  /** Moves to {@code name}, skipping fillers, and checks that it has the expected type. */
  <F extends CopybookField> F next(String name, Class<F> type) {
    skipFillers();
    if (index >= layout.fields().size()) {
      throw new IllegalStateException(layout.name() + ": no field left for " + name);
    }
    CopybookField field = layout.fields().get(index);
    if (!field.name().equals(name) || !type.isInstance(field)) {
      throw new IllegalStateException(
          layout.name() + ": expected " + name + " but the layout has " + field.name());
    }
    index++;
    return type.cast(field);
  }

  int offsetOfLast() {
    return layout.offset(index - 1);
  }

  /** Fails unless every non-filler field was visited. */
  void requireComplete() {
    skipFillers();
    if (index != layout.fields().size()) {
      throw new IllegalStateException(
          layout.name() + ": field " + layout.fields().get(index).name() + " was not mapped");
    }
  }

  private void skipFillers() {
    while (index < layout.fields().size()
        && layout.fields().get(index) instanceof CopybookField.Filler) {
      onFiller(layout.offset(index), layout.fields().get(index).length());
      index++;
    }
  }

  abstract void onFiller(int offset, int length);
}
