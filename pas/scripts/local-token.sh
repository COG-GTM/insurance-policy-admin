#!/usr/bin/env bash
# Mints an RS256 access token for the LOCAL docker-compose stack only, signed with the git-ignored
# key pair init-env.sh generates in pas/.local-jwt/. Shared environments use the enterprise IdP.
#   pas/scripts/local-token.sh "<scopes>" ['<extra JSON claims>']
#   e.g. pas/scripts/local-token.sh "pas.policy.read" '"pas_book":"*"'
set -euo pipefail
cd "$(dirname "$0")/../.."

key=pas/.local-jwt/private.pem
[[ -f "$key" ]] || { echo "local-token: $key missing, run 'make pas-env'" >&2; exit 1; }
scopes="${1:?usage: local-token.sh \"<scopes>\" ['<extra JSON claims>']}"
extra="${2:-}"
issuer="${PAS_JWT_ISSUER:-https://pas-local.invalid/issuer}"
audience="${PAS_JWT_AUDIENCE:-pas-policy-service}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT","kid":"pas-local"}' | b64url)
payload=$(printf '{"iss":"%s","aud":"%s","sub":"local-dev","azp":"pas-local-cli","scope":"%s","iat":%d,"exp":%d%s}' \
  "$issuer" "$audience" "$scopes" "$now" "$((now + 300))" "${extra:+,$extra}" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" -binary | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$signature"
