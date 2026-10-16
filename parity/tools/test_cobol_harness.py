#!/usr/bin/env python3
"""Unit tests for the preprocessor and fixture encoder. No cobc needed."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import cobol_harness as h


class TestParsePic(unittest.TestCase):
    def test_x(self):
        self.assertEqual(h.parse_pic("X(3)"), ("X", 3, 0, False))

    def test_unsigned_int(self):
        self.assertEqual(h.parse_pic("9(8)"), ("9", 8, 0, False))

    def test_signed_v99(self):
        self.assertEqual(h.parse_pic("S9(9)V99"), ("9", 9, 2, True))

    def test_v9n(self):
        self.assertEqual(h.parse_pic("S9(3)V9(4)"), ("9", 3, 4, True))

    def test_bad(self):
        with self.assertRaises(h.HarnessError):
            h.parse_pic("COMP-3")


class TestEncode(unittest.TestCase):
    def test_x_pad(self):
        self.assertEqual(h.encode_field("f", "X(3)", "AU"), "AU ")

    def test_x_too_long(self):
        with self.assertRaises(h.HarnessError):
            h.encode_field("f", "X(3)", "ABCD")

    def test_unsigned_money(self):
        self.assertEqual(h.encode_field("f", "9(3)V99", "12.3"), "01230")

    def test_signed_money(self):
        self.assertEqual(h.encode_field("f", "S9(4)V99", "-123.45"), "-012345")
        self.assertEqual(h.encode_field("f", "S9(4)V99", "123.45"), "+012345")

    def test_too_many_decimals(self):
        with self.assertRaises(h.HarnessError):
            h.encode_field("f", "S9(4)V99", "1.234")

    def test_too_many_int_digits(self):
        with self.assertRaises(h.HarnessError):
            h.encode_field("f", "9(2)", "123")

    def test_negative_unsigned(self):
        with self.assertRaises(h.HarnessError):
            h.encode_field("f", "9(3)", "-1")

    def test_blank(self):
        with self.assertRaises(h.HarnessError):
            h.encode_field("f", "9(3)", "  ")


SAMPLE = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. SAMPLE.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-A PIC X(3).
           EXEC SQL INCLUDE SQLCA END-EXEC.
       LINKAGE SECTION.
       01  DFHCOMMAREA PIC X(10).
       PROCEDURE DIVISION.
       0000-MAIN.
           EXEC CICS ASKTIME ABSTIME(WS-A) END-EXEC
           IF WS-A = DFHRESP(NORMAL)
               CONTINUE
           END-IF
           .
       2000-EXIT.
           EXEC SQL
               SELECT A INTO :WS-A FROM T
           END-EXEC
           .
"""


class TestPreprocess(unittest.TestCase):
    def test_stub_naming(self):
        gen, stubs = h.preprocess_source(SAMPLE)
        names = [n for n, _ in stubs]
        self.assertEqual(names, ["STUB-0000-1", "STUB-2000-1"])
        self.assertIn("COPY HXSTUBS.", gen)
        self.assertIn("COPY HXPROC.", gen)

    def test_sqlca_and_ws_copies(self):
        gen, _ = h.preprocess_source(SAMPLE)
        self.assertIn("COPY HXSQLCA.", gen)
        self.assertNotIn("INCLUDE SQLCA", gen)
        # HXFIX/HXWS inserted before LINKAGE SECTION
        self.assertLess(gen.index("COPY HXFIX."), gen.index("LINKAGE SECTION."))
        self.assertIn("COPY HXWS.", gen)

    def test_dfhresp(self):
        gen, _ = h.preprocess_source(SAMPLE)
        self.assertIn("IF WS-A = 0", gen)

    def test_dfhresp_bad(self):
        bad = SAMPLE.replace("DFHRESP(NORMAL)", "DFHRESP(INVREQ)")
        with self.assertRaises(h.HarnessError):
            h.preprocess_source(bad)

    def test_exec_text_recorded(self):
        _, stubs = h.preprocess_source(SAMPLE)
        self.assertIn("ASKTIME", stubs[0][1])
        self.assertIn("SELECT A", stubs[1][1])


if __name__ == "__main__":
    unittest.main()
