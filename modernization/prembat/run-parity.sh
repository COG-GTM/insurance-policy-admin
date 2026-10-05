#!/usr/bin/env bash
# PREMBAT parity harness: runs identical fixtures through the COBOL
# premium calculation (GnuCOBOL) and the Java PremiumCalculator, then
# diffs the outputs field-for-field.
#
#   ./run-parity.sh                 compile + run COBOL, run Java, diff
#   ./run-parity.sh --update-golden also refresh expected/cobol-golden.psv
#
# Without GnuCOBOL the Java side still runs against the checked-in
# golden file (expected/cobol-golden.psv) via `mvn test`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/cobol/programs/PREMBAT.cbl"
BUILD="$HERE/build"
GOLDEN="$HERE/expected/cobol-golden.psv"
JAVA_OUT="$ROOT/java-facade/target/parity/java-out.psv"
mkdir -p "$BUILD/gen"

cat "$HERE"/fixtures/legacy-cases.dat "$HERE"/fixtures/factor-sweep.dat > "$BUILD/parity-in.dat"

if command -v cobc >/dev/null 2>&1; then
  echo "== [1/4] Extracting calculation logic from cobol/programs/PREMBAT.cbl"
  awk '/^       01  WS-CALC-FIELDS\./{p=1} /EXEC SQL INCLUDE SQLCA/{p=0} p' \
      "$SRC" > "$BUILD/gen/PREMBAT-WS.cpy"
  awk '/^       3200-CALCULATE-PREMIUM\./{p=1} /^       3300-WRITE-PREMIUM-RECORD\./{p=0} p' \
      "$SRC" > "$BUILD/gen/PREMBAT-CALC.cpy"
  sed -e 's/^       3200-CALCULATE-PREMIUM\./       3200-CALCULATE-PREMIUM-FACT./' \
      -e 's/WS-BASE-PREMIUM \* 1\.00/WS-BASE-PREMIUM * WS-P-TERR-FACTOR/' \
      -e 's/WS-TERR-PREMIUM \* 1\.00/WS-TERR-PREMIUM * WS-P-CLASS-FACTOR/' \
      -e 's/WS-CLASS-PREMIUM \* 1\.00/WS-CLASS-PREMIUM * WS-P-EXP-MOD/' \
      "$BUILD/gen/PREMBAT-CALC.cpy" > "$BUILD/gen/PREMBAT-CALC-FACT.cpy"
  for f in WS-P-TERR-FACTOR WS-P-CLASS-FACTOR WS-P-EXP-MOD; do
    if [ "$(grep -c "$f" "$BUILD/gen/PREMBAT-CALC-FACT.cpy")" -ne 1 ]; then
      echo "Factor substitution for $f failed - PREMBAT.cbl changed shape" >&2; exit 2
    fi
  done
  wc -l "$BUILD"/gen/*.cpy | sed 's/^/   /'

  echo "== [2/4] Compiling COBOL harness with $(cobc --version | head -1)"
  cobc -x -std=ibm -I "$ROOT/cobol/copybooks" -I "$BUILD/gen" \
       -o "$BUILD/premprty" "$HERE/harness/PREMPRTY.cbl"

  echo "== [3/4] Running COBOL"
  DD_PARITYIN="$BUILD/parity-in.dat" DD_PARITYOUT="$BUILD/cobol-out.psv" "$BUILD/premprty"
  if [ "${1:-}" = "--update-golden" ]; then
    cp "$BUILD/cobol-out.psv" "$GOLDEN"
    echo "   golden file refreshed: $GOLDEN"
  elif ! diff -q "$GOLDEN" "$BUILD/cobol-out.psv" >/dev/null; then
    echo "   WARNING: live COBOL output differs from checked-in golden file" >&2
    diff "$GOLDEN" "$BUILD/cobol-out.psv" | head -20 >&2
    exit 3
  else
    echo "   live COBOL output matches expected/cobol-golden.psv"
  fi
  COBOL_OUT="$BUILD/cobol-out.psv"
else
  echo "== GnuCOBOL (cobc) not found - using checked-in golden file"
  COBOL_OUT="$GOLDEN"
fi

echo "== [4/4] Running Java PremiumCalculator parity tests"
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"1\.8'; then
  for j in /usr/lib/jvm/java-8-openjdk-amd64 /usr/lib/jvm/temurin-8-jdk-amd64; do
    [ -d "$j" ] && export JAVA_HOME="$j" && break
  done
fi
rm -f "$JAVA_OUT"
if ! (cd "$ROOT/java-facade" && mvn -B test -Dtest='PrembatParityTest,PremiumCalculatorTest' \
      -DfailIfNoTests=false > "$BUILD/mvn-test.log" 2>&1); then
  grep -E 'PARITY|Tests run|FAIL' "$BUILD/mvn-test.log" >&2 || true
  echo "Java tests failed - see $BUILD/mvn-test.log" >&2
  exit 1
fi
grep -E '^PARITY \||Tests run:.*in com' "$BUILD/mvn-test.log" | sed 's/^/   /'

echo
echo "== Field-for-field diff: COBOL vs Java"
if diff "$COBOL_OUT" "$JAVA_OUT" > "$BUILD/parity.diff"; then
  n=$(wc -l < "$COBOL_OUT")
  echo "PARITY OK: $n/$n cases identical (base, territory, class, exp-mod, tax, surcharge, final)"
else
  echo "PARITY FAILED: $(grep -c '^<' "$BUILD/parity.diff") mismatched lines - see $BUILD/parity.diff" >&2
  head -20 "$BUILD/parity.diff" >&2
  exit 1
fi
