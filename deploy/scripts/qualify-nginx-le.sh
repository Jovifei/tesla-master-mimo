#!/usr/bin/env bash
# Validate a public server cert BEFORE selecting its live Nginx include.
# OpenSSL uses OS trust roots (not a --insecure flag). No subjects/keys printed.
# For isolated CI root tests only: SSL_CERT_FILE points to the synthetic test CA.
set -Eeuo pipefail
[[ "$#" -eq 5 ]] || { echo "TLS_CERTIFICATE=INVALID_ARGS"; exit 2; }
chain="$1"; key="$2"; shift 2
# Certbot /etc/letsencrypt/live entries are normally symlinks into archive.\n# Accept only existing nonempty content; trusted chain, dates, SAN and key are\n# independently verified below without ever printing private contents.\nif [[ ! -s "$chain" || ! -s "$key" ]]; then
  echo "TLS_CERTIFICATE=INVALID"
  exit 1
fi
# notBefore, notAfter, hostname and CA/path validity are all checked by
# openssl verify. Require at least another certificate in fullchain for
# public-chain deployment (no leaf-only self-issued certificate).
certificate_count="$(grep -c -- '-----BEGIN CERTIFICATE-----' "$chain" || true)"
if (( certificate_count < 2 )); then
  echo "TLS_CERTIFICATE=INVALID"
  exit 1
fi
if ! openssl x509 -in "$chain" -noout -checkend 86400 >/dev/null 2>&1; then
  echo "TLS_CERTIFICATE=INVALID"
  exit 1
fi
for host in "$@"; do
  if [[ ! "$host" =~ ^[a-zA-Z0-9.-]+$ ]] || \
     ! openssl verify -purpose sslserver -verify_hostname "$host" \
       -untrusted "$chain" "$chain" >/dev/null 2>&1; then
    echo "TLS_CERTIFICATE=INVALID"
    exit 1
  fi
done
cert_public="$(openssl x509 -in "$chain" -noout -pubkey |
  openssl pkey -pubin -outform DER 2>/dev/null | sha256sum | cut -d' ' -f1)" || {
  echo "TLS_CERTIFICATE=INVALID"; exit 1;
}
key_public="$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null |
  sha256sum | cut -d' ' -f1)" || {
  echo "TLS_CERTIFICATE=INVALID"; exit 1;
}
if [[ -z "$cert_public" || "$cert_public" != "$key_public" ]]; then
  echo "TLS_CERTIFICATE=INVALID"
  exit 1
fi
echo "TLS_CERTIFICATE=VALID"
