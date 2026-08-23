#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="${JOURVOLT_PILOT_ENV_FILE:-${script_dir}/.env}"
compose_file="${script_dir}/docker-compose.pilot.example.yml"
age_identity="${JOURVOLT_BACKUP_AGE_IDENTITY:-}"
confirm_token="${JOURVOLT_RESTORE_CONFIRM:-}"

fail() {
  printf 'RESTORE=FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  printf 'Usage: JOURVOLT_BACKUP_AGE_IDENTITY=/path/to/identity.txt JOURVOLT_RESTORE_CONFIRM=I_UNDERSTAND_DATABASE_OVERWRITE %s /path/to/backup.dump.age\n' "$0" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
backup_file="$1"
[[ -f "$backup_file" ]] || fail "backup file does not exist: $backup_file"
command -v docker >/dev/null 2>&1 || fail 'docker command was not found'
command -v age >/dev/null 2>&1 || fail 'age command was not found'
[[ -f "$env_file" ]] || fail "env file does not exist: $env_file"
[[ -f "$compose_file" ]] || fail "compose file does not exist: $compose_file"
[[ -f "$age_identity" ]] || fail 'JOURVOLT_BACKUP_AGE_IDENTITY must point to a private age identity outside the backup directory'
[[ "$confirm_token" == 'I_UNDERSTAND_DATABASE_OVERWRITE' ]] || fail 'explicit restore confirmation is required'

env_file="$(realpath -- "$env_file")"
age_identity="$(realpath -- "$age_identity")"
backup_file="$(realpath -- "$backup_file")"
backup_dir="$(realpath -m -- "${JOURVOLT_BACKUP_DIR:-${script_dir}/backups}")"
case "$age_identity" in
  "$backup_dir"|"$backup_dir"/*)
    fail 'JOURVOLT_BACKUP_AGE_IDENTITY must be outside the backup directory'
    ;;
esac

docker compose --env-file "$env_file" -f "$compose_file" config --quiet \
  || fail 'Pilot Compose configuration is invalid'

age --decrypt -i "$age_identity" "$backup_file" \
  | docker compose --env-file "$env_file" -f "$compose_file" exec -T jourvolt-postgres \
      sh -c 'pg_restore --clean --if-exists --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  || fail 'database restore failed'

printf 'RESTORE=PASS\n'
printf 'RESTORE_SOURCE=%s\n' "$backup_file"
printf 'RESTORE_DESTRUCTIVE=true\n'
