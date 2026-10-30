#!/usr/bin/env bash
set -euo pipefail
umask 077
mode="${1:---run}"
[[ "$#" -le 1 && ( "$mode" == '--run' || "$mode" == '--cleanup-only' ) ]] || exit 64
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted ]] || exit 65
[[ "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == X64 ]] || exit 65
[[ "${GITHUB_EVENT_NAME:-}" == push ]] || exit 65
[[ "${GITHUB_REF:-}" == refs/heads/codex/app-fleet-engine26-ci-20261030 ]] || exit 65
[[ "${GITHUB_RUN_ATTEMPT:-}" == 1 ]] || exit 65
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ && "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || exit 65
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# No pipeline/tee hides Python's status. No host token/config/env reaches Docker.
# The Python finalizer owns its try/finally; the workflow always() step is idempotent.
exec sudo -n env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  /usr/bin/python3 -B "$script_dir/orchestrate.py" "$mode" \
  --source "$script_dir" --evidence "$GITHUB_WORKSPACE/engine26-evidence" \
  --run-id "$GITHUB_RUN_ID" --attempt "$GITHUB_RUN_ATTEMPT" --sha "$GITHUB_SHA" \
  --first-outcome "${ENGINE26_FIRST_OUTCOME:-unknown}" \
  --runner-image "${ImageOS:-unknown}/${ImageVersion:-unknown}"
