package com.acme.insurance.pas.common.copybook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecordLayoutTest {

  private static final Charset EBCDIC = PasCopybooks.HOST_CHARSET;
  private static final RecordLayout LAYOUT =
      RecordLayout.builder("T")
          .field(CopybookField.alphanumeric("NAME", 5))
          .field(CopybookField.date("WHEN"))
          .field(CopybookField.packed("AMT", 5, 2))
          .field(CopybookField.filler(2))
          .build();

  @Test
  void computesLengthAndOffsets() {
    assertThat(LAYOUT.length()).isEqualTo(5 + 8 + 4 + 2);
    assertThat(LAYOUT.offsetOf("WHEN")).isEqualTo(5);
    assertThat(LAYOUT.offsetOf("AMT")).isEqualTo(13);
  }

  @Test
  void zeroAndBlankDatesDecodeToNullAndNullEncodesAsZeros() {
    RecordValues values = new RecordValues().put("NAME", "AB").put("WHEN", null).put("AMT", null);
    byte[] bytes = LAYOUT.encode(values, StandardCharsets.US_ASCII);
    assertThat(new String(bytes, 0, 13, StandardCharsets.US_ASCII)).isEqualTo("AB   00000000");
    RecordValues decoded = LAYOUT.decode(bytes, StandardCharsets.US_ASCII);
    assertThat(decoded.date("WHEN")).isNull();
    assertThat(decoded.decimal("AMT")).isEqualByComparingTo("0");

    byte[] blanks = bytes.clone();
    for (int i = 5; i < 13; i++) {
      blanks[i] = ' ';
    }
    assertThat(LAYOUT.decode(blanks, StandardCharsets.US_ASCII).date("WHEN")).isNull();
  }

  @Test
  void leapDayAndExtremeDatesRoundTripInEbcdic() {
    for (LocalDate date :
        new LocalDate[] {
          LocalDate.of(2024, 2, 29), LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)
        }) {
      RecordValues values = new RecordValues().put("NAME", "X").put("WHEN", date).put("AMT", null);
      assertThat(LAYOUT.decode(LAYOUT.encode(values, EBCDIC), EBCDIC).date("WHEN")).isEqualTo(date);
    }
  }

  @Test
  void rejectsInvalidCalendarDates() {
    byte[] bytes = "AB   20250229".getBytes(StandardCharsets.US_ASCII);
    byte[] record = new byte[LAYOUT.length()];
    System.arraycopy(bytes, 0, record, 0, bytes.length);
    record[13 + 3] = 0x0C;
    assertThatThrownBy(() -> LAYOUT.decode(record, StandardCharsets.US_ASCII))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("T.WHEN")
        .hasMessageContaining("20250229");
  }

  @Test
  void rejectsWrongLengthTextOverflowAndMultiByteCharsets() {
    assertThatThrownBy(() -> LAYOUT.decode(new byte[3], EBCDIC))
        .isInstanceOf(CopybookDataException.class);
    RecordValues tooLong = new RecordValues().put("NAME", "ABCDEF").put("WHEN", null);
    assertThatThrownBy(() -> LAYOUT.encode(tooLong.put("AMT", null), EBCDIC))
        .isInstanceOf(CopybookDataException.class)
        .hasMessageContaining("PIC X(5)");
    assertThatThrownBy(() -> LAYOUT.encode(tooLong, StandardCharsets.UTF_8))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void textEncodesInEbcdic() {
    RecordValues values = new RecordValues().put("NAME", "A1").put("WHEN", null).put("AMT", null);
    byte[] bytes = LAYOUT.encode(values, EBCDIC);
    assertThat(bytes[0]).isEqualTo((byte) 0xC1);
    assertThat(bytes[1]).isEqualTo((byte) 0xF1);
    assertThat(bytes[2]).isEqualTo((byte) 0x40);
    assertThat(bytes[17]).isEqualTo((byte) 0x40);
  }

  @Test
  void valuesToStringListsNamesOnly() {
    assertThat(new RecordValues().put("CUST-TAX-ID", "123456789").toString())
        .isEqualTo("RecordValues[CUST-TAX-ID]");
  }
}
