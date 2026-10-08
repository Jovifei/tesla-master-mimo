#!/usr/bin/env bash
# Hosted isolated build only. Never run this entrypoint on the owner's workstation.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This entrypoint is only for isolated GitHub Actions.' >&2; exit 2; }
root="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$root/android-evidence"
cd "$root/android"
git rev-parse HEAD HEAD^{tree} HEAD^ > "$root/android-evidence/SOURCE_HEAD.txt"
# The wrapper reads systemProp entries before applying normal Gradle CLI flags.
# Remove only workstation proxy entries in this disposable checkout, record the
# exact transformation, and restore even on failure. No business source changes.
backup="$(mktemp)"
cp gradle.properties "$backup"
trap 'cp "$backup" gradle.properties; rm -f "$backup"' EXIT
python3 - <<'PY'
from pathlib import Path
import hashlib
p=Path('gradle.properties')
before=p.read_bytes()
keys={'systemProp.http.proxyHost','systemProp.http.proxyPort','systemProp.https.proxyHost','systemProp.https.proxyPort','systemProp.http.nonProxyHosts'}
after='\n'.join(line for line in before.decode().splitlines() if line.split('=',1)[0] not in keys)+'\n'
p.write_text(after)
Path('../android-evidence/CI_ENV_TRANSFORM.txt').write_text('disposable checkout only; remove workstation Gradle proxy keys\nsource_sha256='+hashlib.sha256(before).hexdigest()+'\nci_sha256='+hashlib.sha256(after.encode()).hexdigest()+'\n')
PY
set +e
bash ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  --no-daemon --continue --max-workers=2 \
  -PJOURVOLT_API_BASE_URL=https://api.teslalink.joviluma.com/ \
  -PJOURVOLT_AUTH_HOST=auth.teslalink.joviluma.com \
  -PMATELINK_PUBLIC_INFO_BASE_URL=https://auth.teslalink.joviluma.com \
  > "$root/android-evidence/gradle.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$root/android-evidence/BUILD_EXIT.txt"
tail -n 90 "$root/android-evidence/gradle.log"
exit "$status"
