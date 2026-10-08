#!/usr/bin/env bash
# Hosted isolated build only. Does not edit workstation network or Gradle settings.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This entrypoint is only for isolated GitHub Actions.' >&2; exit 2; }
root="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$root/android-evidence"
cd "$root/android"
git rev-parse HEAD HEAD^{tree} HEAD^ > "$root/android-evidence/SOURCE_HEAD.txt"
set +e
# CLI system properties take precedence over the repository's workstation-only
# 127.0.0.1 Gradle proxy. No production/network/host configuration is modified.
bash ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  --no-daemon --continue --max-workers=2 \
  -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyPort= \
  -PJOURVOLT_API_BASE_URL=https://api.teslalink.joviluma.com/ \
  -PJOURVOLT_AUTH_HOST=auth.teslalink.joviluma.com \
  -PMATELINK_PUBLIC_INFO_BASE_URL=https://auth.teslalink.joviluma.com \
  > "$root/android-evidence/gradle.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$root/android-evidence/BUILD_EXIT.txt"
tail -n 90 "$root/android-evidence/gradle.log"
exit "$status"
