#!/usr/bin/env bash
# Regenerates the PREMBAT parity golden file from the COBOL harness (needs GnuCOBOL).
# PREMHARN.cbl is a copy of PREMBAT 3200-CALCULATE-PREMIUM with identical PICs;
# only the hard-coded 1.00 factors are replaced by case inputs.
set -euo pipefail
cd "$(dirname "$0")"
out=../resources/prembat-parity.csv
cobc -x -std=ibm PREMHARN.cbl -o "${TMPDIR:-/tmp}/premharn"
{
  echo "policyType,territoryFactor,classFactor,experienceMod,basePremium,territoryPremium,classPremium,modifiedPremium,taxAmount,surcharge,finalPremium"
  "${TMPDIR:-/tmp}/premharn" < prembat-cases.txt | sed 's/, */,/g; s/,0*\([0-9]\)/,\1/g; s/,-0*\([0-9]\)/,-\1/g'
} > "$out"
echo "wrote $out"
