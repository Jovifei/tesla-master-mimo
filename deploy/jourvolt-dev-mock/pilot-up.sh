#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="${JOURVOLT_PILOT_ENV_FILE:-${script_dir}/.env}"
compose_file="${script_dir}/docker-compose.pilot.example.yml"
no_edge='false'
verify_app_link='false'
skip_build='false'

while (($# > 0)); do
  case "$1" in
    --env-file)
      shift
      [[ $# -gt 0 ]] || { printf '%s\n' '--env-file requires a path' >&2; exit 2; }
      env_file="$1"
      ;;
    --no-edge) no_edge='true' ;;
    --verify-app-link) verify_app_link='true' ;;
    --skip-build) skip_build='true' ;;
    *) printf 'Usage: %s [--env-file PATH] [--no-edge] [--verify-app-link] [--skip-build]\n' "$0" >&2; exit 2 ;;
  esac
  shift
done

[[ -f "$env_file" ]] || { printf 'PILOT_DEPLOY=FAIL: env file does not exist: %s\n' "$env_file" >&2; exit 1; }
[[ -f "$compose_file" ]] || { printf 'PILOT_DEPLOY=FAIL: compose file does not exist: %s\n' "$compose_file" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { printf '%s\n' 'PILOT_DEPLOY=FAIL: docker command was not found' >&2; exit 1; }
env_file="$(realpath -- "$env_file")"

preflight_args=(--env-file "$env_file")
if [[ "$no_edge" != 'true' ]]; then preflight_args+=(--verify-dns); fi
if [[ "$verify_app_link" == 'true' ]]; then preflight_args+=(--verify-app-link); fi
bash "${script_dir}/preflight.sh" "${preflight_args[@]}"

compose_args=(--env-file "$env_file" -f "$compose_file")
if [[ "$no_edge" != 'true' ]]; then compose_args+=(--profile edge); fi
docker compose "${compose_args[@]}" config --quiet

up_args=("${compose_args[@]}" up)
if [[ "$skip_build" != 'true' ]]; then up_args+=(--build); fi
up_args+=(-d)
docker compose "${up_args[@]}"

docker compose "${compose_args[@]}" exec -T jourvolt-api wget -q -O - http://127.0.0.1:8080/readyz
printf 'PILOT_DEPLOY=PASS\n'
printf 'The controlled services are running; Tesla OAuth and real vehicle acceptance remain separate gates.\n'
