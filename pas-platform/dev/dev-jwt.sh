#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY_DIR="$ROOT/.dev-keys"
PRIVATE_KEY="$KEY_DIR/private.pem"
PUBLIC_KEY="$KEY_DIR/public.pem"

usage() {
  printf '%s\n' \
    "Usage:" \
    "  dev/dev-jwt.sh keygen [--force]" \
    "  dev/dev-jwt.sh mint [--sub S] [--scope \"policy:read policy:write\"]" \
    "      [--claim name=value]... [--claim-json name=JSON]... [--ttl seconds]" \
    "  dev/dev-jwt.sh --help"
}

fail() {
  printf 'Error: %s\n' "$1" >&2
  exit 2
}

keygen() {
  local force=false
  if [[ "${1:-}" == "--force" ]]; then
    force=true
    shift
  fi
  [[ $# -eq 0 ]] || fail "unexpected keygen arguments"
  mkdir -p "$KEY_DIR"
  if [[ -f "$PRIVATE_KEY" && -f "$PUBLIC_KEY" && "$force" != true ]]; then
    printf 'Development key pair already exists at %s\n' "$KEY_DIR"
    return
  fi
  if [[ ( -e "$PRIVATE_KEY" || -e "$PUBLIC_KEY" ) && "$force" != true ]]; then
    fail "incomplete key pair; use keygen --force to replace it"
  fi
  umask 077
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY"
  openssl pkey -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY"
  chmod 600 "$PRIVATE_KEY"
  chmod 644 "$PUBLIC_KEY"
  printf 'Created development RSA key pair at %s\n' "$KEY_DIR"
}

command="${1:-}"
if [[ "$command" == "--help" || "$command" == "-h" || -z "$command" ]]; then
  usage
  [[ -n "$command" ]] && exit 0 || exit 2
fi
shift

case "$command" in
  keygen)
    keygen "$@"
    ;;
  mint)
    sub="local-agent"
    scopes="policy:read policy:write"
    ttl=3600
    claims=()
    json_claims=()
    while (($#)); do
      case "$1" in
        --sub)
          (($# >= 2)) || fail "missing value for --sub"
          sub="$2"
          shift 2
          ;;
        --scope)
          (($# >= 2)) || fail "missing value for --scope"
          scopes="$2"
          shift 2
          ;;
        --claim)
          (($# >= 2)) || fail "missing value for --claim"
          claims+=("$2")
          shift 2
          ;;
        --claim-json)
          (($# >= 2)) || fail "missing value for --claim-json"
          json_claims+=("$2")
          shift 2
          ;;
        --ttl)
          (($# >= 2)) || fail "missing value for --ttl"
          ttl="$2"
          shift 2
          ;;
        -h|--help)
          usage
          exit 0
          ;;
        *)
          fail "unknown mint argument: $1"
          ;;
      esac
    done
    if [[ ! -f "$PRIVATE_KEY" || ! -f "$PUBLIC_KEY" ]]; then
      "$0" keygen
    fi
    python3 - "$PRIVATE_KEY" "$sub" "$scopes" "$ttl" "${claims[@]}" --json-claims "${json_claims[@]}" <<'PY'
import base64
import json
import subprocess
import sys
import time

private_key, subject, scopes, ttl = sys.argv[1:5]
arguments = sys.argv[5:]
marker = arguments.index("--json-claims")
plain_claims = arguments[:marker]
json_claims = arguments[marker + 1 :]

def pairs(values, parse_json):
    result = {}
    for argument in values:
        name, separator, value = argument.partition("=")
        if not separator or not name:
            raise SystemExit("claims must use name=value")
        if name in {"iss", "sub", "scope", "iat", "exp"}:
            raise SystemExit(f"claim cannot replace a registered field: {name}")
        result[name] = json.loads(value) if parse_json else value
    return result

issued_at = int(time.time())
payload = {
    "iss": "pas-local-dev",
    "sub": subject,
    "scope": scopes,
    "iat": issued_at,
    "exp": issued_at + int(ttl),
}
payload.update(pairs(plain_claims, False))
payload.update(pairs(json_claims, True))
header = {"alg": "RS256", "typ": "JWT", "kid": "pas-local-dev"}

def encode(value):
    return base64.urlsafe_b64encode(
        json.dumps(value, separators=(",", ":")).encode()
    ).rstrip(b"=").decode()

signing_input = f"{encode(header)}.{encode(payload)}"
signature = subprocess.run(
    ["openssl", "dgst", "-sha256", "-sign", private_key],
    input=signing_input.encode(),
    check=True,
    stdout=subprocess.PIPE,
).stdout
jwt = f"{signing_input}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"
print(jwt)
PY
    ;;
  *)
    fail "unknown command: $command"
    ;;
esac
