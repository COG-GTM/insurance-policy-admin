#!/usr/bin/env bash
# Local-only JWT issuer for pas-policy-service (profile "local").
#
#   dev/dev-jwt.sh keygen                 # creates .dev-keys/ (gitignored) with a throwaway RSA pair
#   dev/dev-jwt.sh mint [scopes] [claims] # prints a signed RS256 JWT, valid 1 hour
#
# Examples:
#   dev/dev-jwt.sh mint "policy:read" '"book":["*"]'
#   dev/dev-jwt.sh mint "policy:read policy:pii:read" '"agent_code":"AG1001"'
#
# Issuer/audience match what Compose passes the service: exported PAS_OIDC_ISSUER_URI /
# PAS_OIDC_AUDIENCE, else pas-platform/.env, else the local profile defaults.
# keygen prints "rotated" on stdout when it replaced an existing public key (make dev-up then
# recreates the service). Never use these keys outside a disposable dev environment.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
keys="${PAS_DEV_KEYS_DIR:-$here/.dev-keys}"

from_env_file() {
  [[ -f "$here/.env" ]] || return 0
  sed -n "s/^[[:space:]]*$1=//p" "$here/.env" | tail -n 1 | sed -e 's/^["'\'']//' -e 's/["'\'']$//'
}

issuer="${PAS_OIDC_ISSUER_URI:-$(from_env_file PAS_OIDC_ISSUER_URI)}"
issuer="${issuer:-https://pas-dev-issuer.local}"
audience="${PAS_OIDC_AUDIENCE:-$(from_env_file PAS_OIDC_AUDIENCE)}"
audience="${audience:-pas-api}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

keygen() {
  mkdir -p "$keys"
  chmod 700 "$keys"
  if [[ ! -f "$keys/jwt-dev-private.pem" ]]; then
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$keys/jwt-dev-private.pem" 2>/dev/null
    chmod 600 "$keys/jwt-dev-private.pem"
  fi
  local public
  public=$(openssl pkey -in "$keys/jwt-dev-private.pem" -pubout)
  if [[ -f "$keys/jwt-dev-public.pem" ]] && [[ "$(cat "$keys/jwt-dev-public.pem")" != "$public" ]]; then
    echo rotated
  fi
  printf '%s\n' "$public" >"$keys/jwt-dev-public.pem"
  chmod 644 "$keys/jwt-dev-public.pem"
  echo "Dev JWT keys in $keys" >&2
}

mint() {
  local scopes="${1:-policy:read}" extra="${2:-}"
  if [[ ! -f "$keys/jwt-dev-private.pem" ]]; then
    echo "No dev key pair in $keys: run 'make dev-up' (or dev/dev-jwt.sh keygen and restart pas-policy-service)." >&2
    exit 1
  fi
  local now exp header payload signing_input sig
  now=$(date +%s)
  exp=$((now + 3600))
  header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
  payload=$(printf '{"iss":"%s","aud":"%s","sub":"dev-user","iat":%d,"exp":%d,"scope":"%s"%s}' \
    "$issuer" "$audience" "$now" "$exp" "$scopes" "${extra:+,$extra}" | b64url)
  signing_input="$header.$payload"
  sig=$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$keys/jwt-dev-private.pem" | b64url)
  printf '%s.%s\n' "$signing_input" "$sig"
}

case "${1:-}" in
  keygen) keygen ;;
  mint) shift; mint "$@" ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
