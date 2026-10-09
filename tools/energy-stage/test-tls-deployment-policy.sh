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
for script in "$root/deploy/scripts/setup-root.sh" "$root/deploy/scripts/verify-public.sh" "$policy" "$root/deploy/scripts/tls-nginx-transaction.sh" "$root/deploy/scripts/qualify-nginx-le.sh" "$root/deploy/scripts/renew-qualified-reload.sh"; do
  bash -n "$script"
done
# Guard both integration points; a test of an orphan helper is not sufficient.
grep -Fq 'tls-include-policy.sh' "$root/deploy/scripts/setup-root.sh"
grep -Fq 'qualify-public-tls.py' "$root/deploy/scripts/verify-public.sh"
grep -Fq 'tls-nginx-transaction.sh' "$root/deploy/scripts/setup-root.sh"
grep -Fq 'qualify-nginx-le.sh' "$root/deploy/scripts/setup-root.sh"
grep -Fq 'check-socket-port.py' "$root/deploy/scripts/verify-public.sh"
grep -Fq 'renew-qualified-reload.sh' "$root/deploy/scripts/setup-root.sh"
if grep -Eq '(curl -k|curl -sk|bash -c .*PUBLIC_IP|PASS_WITH_SELFSIGNED)' "$root/deploy/scripts/verify-public.sh"; then exit 1; fi
echo 'TLS_DEPLOYMENT_POLICY=PASS'
