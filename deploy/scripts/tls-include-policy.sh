#!/usr/bin/env bash
# Pure fail-closed choice for TLS bootstrap; no reads, writes, network or secrets.
set -euo pipefail
if [[ "$#" -ne 2 || ( "$2" != true && "$2" != false ) ]]; then exit 2; fi
case "$1" in
  letsencrypt)
    # Never replace a formerly active real certificate with a placeholder.
    if [[ "$2" != true ]]; then exit 1; fi
    printf 'letsencrypt\n' ;;
  selfsigned|absent)
    if [[ "$2" == true ]]; then printf 'letsencrypt\n'; else printf 'selfsigned\n'; fi ;;
  *) exit 1 ;;
esac
