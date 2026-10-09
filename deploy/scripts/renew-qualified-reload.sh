#!/usr/bin/env bash
# Certbot deploy hook installed ONLY in a separately approved server rollout.
# Never modify Nginx include, certificate material, trust store or proxy config.
set -Eeuo pipefail
active=/etc/nginx/conf.d/jourvolt-ssl.inc
managed=/etc/nginx/conf.d/jourvolt-ssl.le.inc
selfsigned=/etc/nginx/conf.d/jourvolt-ssl.selfsigned.inc
qualify=/etc/jourvolt/qualify-nginx-le.sh
if [[ ! -f "$active" || ! -f "$managed" || ! -f "$qualify" ]]; then
  echo "TLS_RENEW_HOOK=MANAGED_CONFIG_UNAVAILABLE"; exit 1
fi
if cmp -s "$active" "$selfsigned"; then
  echo "TLS_RENEW_HOOK=INACTIVE_LE_NOOP"; exit 0
fi
if ! cmp -s "$active" "$managed"; then
  echo "TLS_RENEW_HOOK=UNRECOGNIZED_ACTIVE_INCLUDE"; exit 1
fi
if ! bash "$qualify" \
     /etc/letsencrypt/live/jourvolt/fullchain.pem \
     /etc/letsencrypt/live/jourvolt/privkey.pem \
     teslalink.joviluma.com api.teslalink.joviluma.com \
     auth.teslalink.joviluma.com >/dev/null 2>&1; then
  echo "TLS_RENEW_HOOK=INVALID_CHAIN_KEEP_RUNNING"; exit 1
fi
if ! nginx -t >/dev/null 2>&1; then
  echo "TLS_RENEW_HOOK=NGINX_CONFIG_INVALID"; exit 1
fi
systemctl reload nginx >/dev/null 2>&1 || {
  echo "TLS_RENEW_HOOK=RELOAD_FAILED"; exit 1;
}
echo "TLS_RENEW_HOOK=VALIDATED_RELOAD"
