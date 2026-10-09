#!/usr/bin/env bash
# Existing approved LE include after successful Certbot issuance: refuse
# to claim activation when Nginx validation OR reload fails.
# No content, key, subject, token, or effective config is emitted.
set -Eeuo pipefail
if ! sudo nginx -t >/dev/null 2>&1; then
  echo "TLS_LE_ACTIVATION=NGINX_TEST_FAILED"
  exit 1
fi
if ! sudo systemctl reload nginx >/dev/null 2>&1; then
  echo "TLS_LE_ACTIVATION=RELOAD_FAILED"
  exit 1
fi
echo "TLS_LE_ACTIVATION=VALIDATED_RELOAD"
