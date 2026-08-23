#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
public_root="${script_dir}/public"
output_path="${public_root}/.well-known/assetlinks.json"
fingerprint=''
what_if='false'

usage() {
  printf 'Usage: %s --fingerprint HEX_OR_COLON_FORMAT [--output PATH] [--what-if]\n' "$0" >&2
}

fail() {
  printf 'ASSETLINKS_WRITE=FAIL: %s\n' "$1" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --fingerprint)
      shift
      (($# > 0)) || { usage; exit 2; }
      fingerprint="$1"
      ;;
    --output)
      shift
      (($# > 0)) || { usage; exit 2; }
      output_path="$1"
      ;;
    --what-if) what_if='true' ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

[[ -n "$fingerprint" ]] || { usage; exit 2; }
hex="$(printf '%s' "$fingerprint" | tr -d ' :-' | tr '[:lower:]' '[:upper:]')"
[[ "$hex" =~ ^[0-9A-F]{64}$ ]] || fail 'fingerprint must contain exactly 32 bytes of hexadecimal data'

normalized=''
for ((index=0; index<${#hex}; index+=2)); do
  [[ -z "$normalized" ]] || normalized+=':'
  normalized+="${hex:index:2}"
done

if [[ "$output_path" != /* ]]; then
  output_path="${script_dir}/${output_path}"
fi
if command -v realpath >/dev/null 2>&1; then
  output_path="$(realpath -m -- "$output_path")"
  public_root="$(realpath -m -- "$public_root")"
fi
case "$output_path" in
  "$public_root"/*) ;;
  *) fail 'output path must remain inside the deployment public directory' ;;
esac

if [[ "$what_if" == 'true' ]]; then
  printf 'ASSETLINKS_OUTPUT=%s\n' "$output_path"
  printf 'ASSETLINKS_FINGERPRINT=%s\n' "$normalized"
  printf 'ASSETLINKS_WRITE=SKIPPED\n'
  exit 0
fi

mkdir -p -- "$(dirname -- "$output_path")"
printf '[\n  {\n    "relation": [\n      "delegate_permission/common.handle_all_urls"\n    ],\n    "target": {\n      "namespace": "android_app",\n      "package_name": "com.matelink",\n      "sha256_cert_fingerprints": [\n        "%s"\n      ]\n    }\n  }\n]\n' "$normalized" > "$output_path"
chmod 600 -- "$output_path"
printf 'ASSETLINKS_OUTPUT=%s\n' "$output_path"
printf 'ASSETLINKS_FINGERPRINT=%s\n' "$normalized"
printf 'ASSETLINKS_WRITE=PASS\n'
