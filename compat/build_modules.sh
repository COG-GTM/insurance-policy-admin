#!/usr/bin/env bash
# Build and test every Maven module with the JDK it declares.
# Uses JAVA_HOME_<ver>_X64 (set by actions/setup-java) when present.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
status=0
for pom in $(git ls-files '*pom.xml' | grep -v '/target/'); do
  dir=$(dirname "$pom")
  # Skip child modules of an aggregator; the parent build covers them.
  parent=$(dirname "$dir"); [ "$dir" != "." ] && [ -f "$parent/pom.xml" ] && [ "$parent" != "." ] && continue
  ver=$(grep -oPm1 '<(java\.version|maven\.compiler\.release|maven\.compiler\.source)>\K[^<]+' "$pom" || echo 21)
  ver=${ver#1.}
  var="JAVA_HOME_${ver}_X64"
  jh="${!var:-${JAVA_HOME:-}}"
  echo "::group::$dir (JDK $ver)"
  if ! JAVA_HOME="$jh" mvn -B -ntp -f "$pom" verify; then status=1; echo "::error::build/tests failed in $dir"; fi
  echo "::endgroup::"
done
exit $status
