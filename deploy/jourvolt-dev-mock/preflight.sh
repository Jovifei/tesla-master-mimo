#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="${JOURVOLT_PILOT_ENV_FILE:-${script_dir}/.env}"
compose_file="${script_dir}/docker-compose.pilot.example.yml"
verify_app_link='false'
verify_dns='false'
skip_compose='false'
failures=()

while (($# > 0)); do
  case "$1" in
    --verify-app-link) verify_app_link='true' ;;
    --verify-dns) verify_dns='true' ;;
    --skip-compose) skip_compose='true' ;;
    --env-file)
      shift
      [[ $# -gt 0 ]] || { printf '%s\n' '--env-file requires a path' >&2; exit 2; }
      env_file="$1"
      ;;
    *) printf 'Usage: %s [--env-file PATH] [--verify-app-link] [--verify-dns] [--skip-compose]\n' "$0" >&2; exit 2 ;;
  esac
  shift
done

fail() { failures+=("$1"); }

[[ -f "$env_file" ]] || fail "env file does not exist: $env_file"
if [[ "$skip_compose" != 'true' && ! -f "$compose_file" ]]; then
  fail "compose file does not exist: $compose_file"
fi
if [[ -f "$env_file" ]]; then env_file="$(realpath -- "$env_file")"; fi

env_value() {
  local key="$1" line value
  line="$(grep -E "^${key}=" "$env_file" | tail -n 1 || true)"
  value="${line#*=}"
  if [[ ${#value} -ge 2 ]]; then
    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] ||
       [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
      value="${value:1:${#value}-2}"
    fi
  fi
  printf '%s' "$value"
}

required_names=(
  DATABASE_URL POSTGRES_PASSWORD TESLA_CLIENT_ID TESLA_CLIENT_SECRET
  TESLA_REDIRECT_URI JOURVOLT_APP_LINK_URI JOURVOLT_TOKEN_KEY_BASE64
  JOURVOLT_API_DOMAIN JOURVOLT_APP_DOMAIN JOURVOLT_ACME_EMAIL
)
declare -A values=()
for name in "${required_names[@]}"; do
  value=''
  if [[ -f "$env_file" ]]; then value="$(env_value "$name")"; fi
  values["$name"]="$value"
  [[ -n "$value" ]] || fail "Missing $name"
  if [[ "$value" == *CHANGE_THIS* || "$value" == *REPLACE_WITH* ||
        "$value" == *example.com* || "$value" == *'<'*'>'* ]]; then
    fail "$name still contains a placeholder"
  fi
done

mock="$(env_value JOURVOLT_ENABLE_MOCK)"
[[ "${mock,,}" == 'false' ]] || fail 'JOURVOLT_ENABLE_MOCK must be explicitly false'
mock_history="$(env_value JOURVOLT_ENABLE_MOCK_HISTORY)"
[[ "${mock_history,,}" != 'true' ]] || fail 'JOURVOLT_ENABLE_MOCK_HISTORY must not be true in Pilot'

public_https_url() {
  local name="$1" value="$2" host
  [[ "$value" =~ ^https://[^/[:space:]]+/[^[:space:]]+$ ]] || { fail "$name must be an absolute HTTPS URL"; return; }
  host="${value#https://}"
  host="${host%%/*}"
  case "${host,,}" in
    localhost|127.0.0.1|::1|0.0.0.0|*.local|10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) fail "$name must not use a loopback, local, or private host" ;;
    *example.com|*example.org|*example.net) fail "$name still uses an example hostname" ;;
  esac
}

public_host() {
  local name="$1" host="$2"
  [[ -n "$host" && ! "$host" =~ [/:?#[:space:]] ]] || { fail "$name must be a hostname without scheme or path"; return; }
  case "${host,,}" in
    localhost|127.0.0.1|::1|0.0.0.0|*.local|10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*) fail "$name must not use a loopback, local, or private host" ;;
    *example.com|*example.org|*example.net) fail "$name still uses an example hostname" ;;
  esac
}

redirect="${values[TESLA_REDIRECT_URI]}"
app_link="${values[JOURVOLT_APP_LINK_URI]}"
api_domain="${values[JOURVOLT_API_DOMAIN]}"
app_domain="${values[JOURVOLT_APP_DOMAIN]}"
public_https_url TESLA_REDIRECT_URI "$redirect"
public_https_url JOURVOLT_APP_LINK_URI "$app_link"
public_host JOURVOLT_API_DOMAIN "$api_domain"
public_host JOURVOLT_APP_DOMAIN "$app_domain"
[[ "$redirect" == "https://${api_domain}/v1/auth/tesla/callback" ]] || fail 'TESLA_REDIRECT_URI must match JOURVOLT_API_DOMAIN and the callback path'
[[ "$app_link" == "https://${app_domain}/oauth/callback" ]] || fail 'JOURVOLT_APP_LINK_URI must match JOURVOLT_APP_DOMAIN and the App Link path'

token_key="${values[JOURVOLT_TOKEN_KEY_BASE64]}"
if [[ -n "$token_key" ]]; then
  key_tmp="$(mktemp)"
  trap 'rm -f -- "$key_tmp"' EXIT
  if ! command -v base64 >/dev/null 2>&1 || ! printf '%s' "$token_key" | base64 -d >"$key_tmp" 2>/dev/null; then
    fail 'JOURVOLT_TOKEN_KEY_BASE64 is not valid standard Base64'
  elif [[ "$(wc -c <"$key_tmp")" -ne 32 ]]; then
    fail 'JOURVOLT_TOKEN_KEY_BASE64 must decode to exactly 32 bytes'
  fi
fi

asset_links_path="${script_dir}/public/.well-known/assetlinks.json"
if [[ ! -f "$asset_links_path" ]]; then
  fail 'missing public/.well-known/assetlinks.json for formal com.matelink App Link'
elif ! grep -Eq '"package_name"[[:space:]]*:[[:space:]]*"com\.matelink"' "$asset_links_path" ||
     ! grep -Eq '([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}' "$asset_links_path"; then
  fail 'assetlinks.json must declare com.matelink with a release SHA-256 fingerprint'
fi

public_root_value="$(env_value JOURVOLT_PUBLIC_ROOT)"
if [[ -z "$public_root_value" ]]; then
  public_root="${script_dir}/../../web_matelink/public"
elif [[ "$public_root_value" = /* ]]; then
  public_root="$public_root_value"
else
  public_root="${script_dir}/${public_root_value}"
fi
if [[ ! -d "$public_root" ]]; then
  fail "static public root does not exist: $public_root"
else
  public_root="$(realpath -- "$public_root")"
  for legal_page in terms privacy; do
    legal_file="${public_root}/${legal_page}/index.html"
    [[ -f "$legal_file" ]] || fail "missing published legal page: ${public_root}/${legal_page}/index.html"
  done
fi

if [[ "$verify_dns" == 'true' ]]; then
  command -v getent >/dev/null 2>&1 || fail 'getent is required for public DNS verification'
  if command -v getent >/dev/null 2>&1; then
    getent ahosts "$api_domain" >/dev/null 2>&1 || fail "JOURVOLT_API_DOMAIN has no public DNS record: $api_domain"
    getent ahosts "$app_domain" >/dev/null 2>&1 || fail "JOURVOLT_APP_DOMAIN has no public DNS record: $app_domain"
  fi
fi

if [[ "$verify_app_link" == 'true' ]]; then
  command -v curl >/dev/null 2>&1 || fail 'curl is required for App Link verification'
  remote_asset_links="$(curl --fail --silent --show-error --max-time 15 "https://${app_domain}/.well-known/assetlinks.json" || true)"
  if [[ -z "$remote_asset_links" ]] ||
     ! grep -Eq '"package_name"[[:space:]]*:[[:space:]]*"com\.matelink"' <<<"$remote_asset_links" ||
     ! grep -Eq '([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}' <<<"$remote_asset_links"; then
    fail "could not verify formal App Link at https://${app_domain}/.well-known/assetlinks.json"
  fi
  for legal_page in terms privacy; do
    remote_legal_page="$(curl --fail --silent --show-error --max-time 15 "https://${app_domain}/${legal_page}/" || true)"
    if [[ -z "$remote_legal_page" ]] || ! grep -q '<html' <<<"$remote_legal_page"; then
      fail "could not verify published legal page at https://${app_domain}/${legal_page}/"
    fi
  done
fi

if [[ "$skip_compose" != 'true' ]]; then
  command -v docker >/dev/null 2>&1 || fail 'docker command was not found'
  if command -v docker >/dev/null 2>&1; then
    docker compose --env-file "$env_file" -f "$compose_file" config --quiet || fail 'Docker Compose template validation failed'
  fi
fi

if ((${#failures[@]} > 0)); then
  printf 'PREFLIGHT=FAIL\n'
  printf -- '- %s\n' "${failures[@]}"
  exit 1
fi

printf 'PREFLIGHT=PASS\n'
printf 'No secret values were printed.\n'
