#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
policy="$root/deploy/scripts/tls-include-policy.sh"
choice() {
  local got
  got="$(bash "$policy" "$1" "$2")"
  [[ "$got" == "$3" ]] || { echo 'tls selection contract failed'; exit 1; }
}
choice absent false selfsigned
choice selfsigned false selfsigned
choice absent true letsencrypt
choice selfsigned true letsencrypt
choice letsencrypt true letsencrypt
if bash "$policy" letsencrypt false >/dev/null 2>&1; then exit 1; fi
if bash "$policy" unknown true >/dev/null 2>&1; then exit 1; fi
if bash "$policy" selfsigned unknown >/dev/null 2>&1; then exit 1; fi
for script in "$root/deploy/scripts/setup-root.sh" "$root/deploy/scripts/verify-public.sh" "$policy"; do
  bash -n "$script"
done
# Guard both integration points; a test of an orphan helper is not sufficient.
grep -Fq 'tls-include-policy.sh' "$root/deploy/scripts/setup-root.sh"
grep -Fq 'qualify-public-tls.py" --live' "$root/deploy/scripts/verify-public.sh"
if grep -Eq 'CURL="curl[^"]* -[a-zA-Z]*k' "$root/deploy/scripts/verify-public.sh"; then exit 1; fi
echo 'TLS_DEPLOYMENT_POLICY=PASS'
