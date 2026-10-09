#!/usr/bin/env bash
# Scoped, idempotent Nginx certificate/config change with exact on-disk rollback.
# Arguments: conf.d directory, rendered main config, active cert include,
#            managed selfsigned include, managed LE include.
# Production invocation is a SEPARATELY APPROVED operator action.
set -Eeuo pipefail
umask 077
[[ "$#" -eq 5 ]] || { echo "TLS_TRANSACTION=INVALID_ARGS"; exit 2; }
dest_dir="$1"
[[ -d "$dest_dir" && ! -L "$dest_dir" ]] || { echo "TLS_TRANSACTION=INVALID_DIRECTORY"; exit 2; }
names=(jourvolt.conf jourvolt-ssl.inc jourvolt-ssl.selfsigned.inc jourvolt-ssl.le.inc)
sources=("$2" "$3" "$4" "$5")
for index in 0 1 2 3; do
  [[ -f "${sources[$index]}" && ! -L "${sources[$index]}" ]] || {
    echo "TLS_TRANSACTION=INVALID_SOURCE"; exit 2;
  }
  if sudo test -L "$dest_dir/${names[$index]}"; then
    echo "TLS_TRANSACTION=SYMLINK_REJECTED"; exit 1
  fi
done

changed=0
for index in 0 1 2 3; do
  if ! sudo cmp -s "${sources[$index]}" "$dest_dir/${names[$index]}"; then changed=1; fi
done
# Even a no-op must reject an invalid live-certificate/config baseline.
if ! sudo nginx -t >/dev/null 2>&1; then
  echo "TLS_TRANSACTION=INVALID_BASELINE"; exit 1
fi
if [[ "$changed" -eq 0 ]]; then
  echo "TLS_TRANSACTION=NOOP"
  exit 0
fi
backup="$(sudo mktemp -d "$dest_dir/.jourvolt-tls-rollback.XXXXXXXX")"
sudo chmod 0700 "$backup"
for index in 0 1 2 3; do
  name="${names[$index]}"
  if sudo test -e "$dest_dir/$name"; then
    sudo cp -a -- "$dest_dir/$name" "$backup/$name"
  else
    sudo touch "$backup/$name.absent"
  fi
done
pending=""
rollback() {
  local rc="$1" index name
  trap - EXIT ERR
  if [[ "$rc" -eq 0 ]]; then
    if [[ -n "$pending" ]]; then sudo rm -f -- "$pending"; fi
    echo "TLS_TRANSACTION=APPLIED"
    return
  fi
  if [[ -n "$pending" ]]; then sudo rm -f -- "$pending" >/dev/null 2>&1 || :; fi
  # Restore exactly the four owned files, never unrelated Nginx vhosts.
  for index in 0 1 2 3; do
    name="${names[$index]}"
    if sudo test -f "$backup/$name.absent"; then
      sudo rm -f -- "$dest_dir/$name" || :
    else
      sudo cp -a -- "$backup/$name" "$dest_dir/$name" || :
    fi
  done
  if sudo nginx -t >/dev/null 2>&1 && sudo systemctl reload nginx >/dev/null 2>&1; then
    echo "TLS_TRANSACTION=ROLLED_BACK"
  else
    echo "TLS_TRANSACTION=ROLLBACK_REQUIRES_OPERATOR"
  fi
}
trap 'rollback "$?"' EXIT
for index in 0 1 2 3; do
  name="${names[$index]}"
  if sudo cmp -s "${sources[$index]}" "$dest_dir/$name"; then continue; fi
  pending="$(sudo mktemp "$dest_dir/.jourvolt-nginx-new.XXXXXXXX")"
  sudo cp -- "${sources[$index]}" "$pending"
  sudo chmod 0644 "$pending"
  sudo mv -f -- "$pending" "$dest_dir/$name"
  pending=""
done
# nginx -t cannot be bypassed by a successful TCP health response.
sudo nginx -t >/dev/null 2>&1
sudo systemctl reload nginx >/dev/null 2>&1
# Keep root-only backups for independent rollback and hashing; do not print paths.
