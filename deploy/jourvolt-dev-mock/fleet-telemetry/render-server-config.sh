#!/bin/sh
set -eu

: "${TELEMETRY_MQTT_URL:?TELEMETRY_MQTT_URL is required}"
: "${TELEMETRY_MQTT_USERNAME:?TELEMETRY_MQTT_USERNAME is required}"
: "${TELEMETRY_MQTT_PASSWORD:?TELEMETRY_MQTT_PASSWORD is required}"
: "${TELEMETRY_MQTT_TOPIC_BASE:?TELEMETRY_MQTT_TOPIC_BASE is required}"
: "${TELEMETRY_PUBLIC_PORT:?TELEMETRY_PUBLIC_PORT is required}"
: "${TELEMETRY_SERVER_CERT_PATH:?TELEMETRY_SERVER_CERT_PATH is required}"
: "${TELEMETRY_SERVER_KEY_PATH:?TELEMETRY_SERVER_KEY_PATH is required}"
: "${FLEET_TELEMETRY_IMAGE:?FLEET_TELEMETRY_IMAGE is required}"

if ! printf '%s' "$FLEET_TELEMETRY_IMAGE" | grep -Eq '^[^@]+@sha256:[0-9a-f]{64}$'; then
  echo 'FLEET_TELEMETRY_IMAGE must be pinned by sha256 digest' >&2
  exit 1
fi
if [ ! -r "$TELEMETRY_SERVER_CERT_PATH" ]; then
  echo 'missing or unreadable telemetry certificate' >&2
  exit 1
fi
if [ ! -r "$TELEMETRY_SERVER_KEY_PATH" ]; then
  echo 'missing or unreadable telemetry private key' >&2
  exit 1
fi

broker="${TELEMETRY_MQTT_URL#*://}"
case "$broker" in
  */*|*\?*|*#*) echo 'invalid MQTT broker URL' >&2; exit 1 ;;
esac

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/[\t]/\\t/g; s/[\r]/\\r/g; s/[\n]/\\n/g'
}

sed \
  -e "s|__PUBLIC_PORT__|${TELEMETRY_PUBLIC_PORT}|g" \
  -e "s|__SERVER_CERT_PATH__|$(json_escape "$TELEMETRY_SERVER_CERT_PATH")|g" \
  -e "s|__SERVER_KEY_PATH__|$(json_escape "$TELEMETRY_SERVER_KEY_PATH")|g" \
  -e "s|__MQTT_BROKER__|$(json_escape "$broker")|g" \
  -e "s|__MQTT_USERNAME__|$(json_escape "$TELEMETRY_MQTT_USERNAME")|g" \
  -e "s|__MQTT_PASSWORD__|$(json_escape "$TELEMETRY_MQTT_PASSWORD")|g" \
  -e "s|__MQTT_TOPIC_BASE__|$(json_escape "$TELEMETRY_MQTT_TOPIC_BASE")|g" \
  /template/server_config.json.template > /rendered/server_config.json

# The fleet-telemetry process runs as the deploy user (uid 1000), not root, and
# reads this file from the shared rendered directory. Keep it readable by that
# user while staying private to the host (the rendered directory is 0700).
chmod 0640 /rendered/server_config.json
chown "${JOURVOLT_UID:-1000}:${JOURVOLT_GID:-1000}" /rendered/server_config.json 2>/dev/null || true
