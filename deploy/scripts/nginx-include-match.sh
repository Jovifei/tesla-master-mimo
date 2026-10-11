#!/usr/bin/env bash
# Compare only exact Nginx SSL directives, ignoring comments/formatting.
# No source contents or certificate paths are emitted. Extra directives fail.
set -euo pipefail
[[ "$#" -eq 2 ]] || exit 2
[[ -f "$1" && -f "$2" ]] || exit 1
normalize() {
  awk '{
    sub(/#.*/, "", $0)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
    gsub(/[[:space:]]+/, " ", $0)
    if (length($0)) print $0
  }' "$1"
}
# Installed active include may be root-owned; trusted repo copy read locally.
actual="$(sudo awk '{
    sub(/#.*/, "", $0)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
    gsub(/[[:space:]]+/, " ", $0)
    if (length($0)) print $0
}' "$1")" || exit 1
expected="$(normalize "$2")" || exit 1
[[ -n "$expected" && "$actual" == "$expected" ]]
