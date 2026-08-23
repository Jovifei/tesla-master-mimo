#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="${JOURVOLT_PILOT_ENV_FILE:-${script_dir}/.env}"
compose_file="${script_dir}/docker-compose.pilot.example.yml"
backup_dir="${JOURVOLT_BACKUP_DIR:-${script_dir}/backups}"
age_recipient="${JOURVOLT_BACKUP_AGE_RECIPIENT:-}"
object_remote="${JOURVOLT_BACKUP_RCLONE_REMOTE:-}"
backup_kind='daily'
prune='false'
require_upload='false'

while (($# > 0)); do
  case "$1" in
    --weekly) backup_kind='weekly' ;;
    --prune) prune='true' ;;
    --require-upload) require_upload='true' ;;
    *) printf 'Usage: %s [--weekly] [--prune] [--require-upload]\n' "$0" >&2; exit 2 ;;
  esac
  shift
done

fail() {
  printf 'BACKUP=FAIL: %s\n' "$1" >&2
  exit 1
}

command -v docker >/dev/null 2>&1 || fail 'docker command was not found'
command -v age >/dev/null 2>&1 || fail 'age command was not found'
[[ -f "$env_file" ]] || fail "env file does not exist: $env_file"
[[ -f "$compose_file" ]] || fail "compose file does not exist: $compose_file"
[[ -n "$age_recipient" ]] || fail 'JOURVOLT_BACKUP_AGE_RECIPIENT is required'
if [[ "$require_upload" == 'true' && -z "$object_remote" ]]; then
  fail 'JOURVOLT_BACKUP_RCLONE_REMOTE is required when upload is mandatory'
fi
if [[ -n "$object_remote" ]]; then
  command -v rclone >/dev/null 2>&1 || fail 'rclone command was not found'
  [[ "$object_remote" != *CHANGE_THIS* ]] || fail 'JOURVOLT_BACKUP_RCLONE_REMOTE still contains a placeholder'
fi

env_file="$(realpath -- "$env_file")"
mkdir -p -- "$backup_dir"
chmod 700 -- "$backup_dir"

docker compose --env-file "$env_file" -f "$compose_file" config --quiet \
  || fail 'Pilot Compose configuration is invalid'

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
tmp_dir="$(mktemp -d "${backup_dir%/}/.jourvolt-backup.XXXXXX")"
tmp_archive="${tmp_dir}/postgres.dump.age"
final_archive="${backup_dir%/}/jourvolt-postgres-${backup_kind}-${timestamp}.dump.age"
cleanup() {
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

docker compose --env-file "$env_file" -f "$compose_file" exec -T jourvolt-postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner --no-acl' \
  | age -r "$age_recipient" -o "$tmp_archive" \
  || fail 'encrypted PostgreSQL dump failed'

[[ -s "$tmp_archive" ]] || fail 'encrypted backup is empty'
mv -- "$tmp_archive" "$final_archive"
chmod 600 -- "$final_archive"

if [[ -n "$object_remote" ]]; then
  remote_archive="${object_remote%/}/$(basename -- "$final_archive")"
  rclone copyto "$final_archive" "$remote_archive" \
    || fail 'encrypted backup upload failed'
  printf 'BACKUP_UPLOAD=PASS\n'
else
  printf 'BACKUP_UPLOAD=SKIPPED\n'
fi

if [[ "$prune" == 'true' ]]; then
  mapfile -t archives < <(
    find "$backup_dir" -maxdepth 1 -type f \
      -name "jourvolt-postgres-${backup_kind}-*.dump.age" -print | sort -r
  )
  keep=7
  if [[ "$backup_kind" == 'weekly' ]]; then keep=4; fi
  for ((index = keep; index < ${#archives[@]}; index++)); do
    rm -f -- "${archives[$index]}"
  done
fi

printf 'BACKUP=PASS\n'
printf 'BACKUP_FILE=%s\n' "$final_archive"
printf 'BACKUP_ENCRYPTION=age_recipient\n'
printf 'BACKUP_PRIVATE_KEY_STORED_WITH_ARCHIVE=no\n'
if [[ "$prune" == 'true' ]]; then
  printf 'BACKUP_RETENTION=%s\n' "$keep"
else
  printf 'BACKUP_RETENTION=unchanged\n'
fi
