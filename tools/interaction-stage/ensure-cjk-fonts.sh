#!/usr/bin/env bash
# Disposable GitHub-hosted runner only. Never alter an Owner machine or apt sources.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || {
  echo 'CJK setup is restricted to the disposable GitHub-hosted runner' >&2; exit 2;
}
font_available() {
  local family
  family="$(timeout --signal=TERM --kill-after=2s 5s fc-match -f '%{family}\n' 'Noto Sans CJK SC')" || return 1
  printf 'CJK_RENDER_FAMILY=%s\n' "$family"
  [[ "$family" == *'Noto Sans CJK'* ]]
}
if font_available; then exit 0; fi
# Hosted images already carry package indexes. Do not block every run on unrelated
# package feeds before trying the single required package. A failed install gets
# one bounded metadata refresh and one bounded retry; never an unbounded loop.
apt=(sudo env DEBIAN_FRONTEND=noninteractive apt-get
  -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20
  -o DPkg::Lock::Timeout=20)
if ! timeout --signal=TERM --kill-after=10s 150s "${apt[@]}" install --no-install-recommends -y fonts-noto-cjk; then
  echo 'CJK_FIRST_INSTALL_FAILED: refreshing indexes once within 90 seconds' >&2
  timeout --signal=TERM --kill-after=10s 90s "${apt[@]}" update -qq
  timeout --signal=TERM --kill-after=10s 150s "${apt[@]}" install --no-install-recommends -y fonts-noto-cjk
fi
font_available || { echo 'CJK package completed without the required font family; visual gate fails' >&2; exit 1; }
