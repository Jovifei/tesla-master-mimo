#!/usr/bin/env bash
# Called as ONE shell command by reactivecircus/android-emulator-runner.
# That action executes each YAML script line in a separate sh -c process, so
# writing 'cd android' on a separate line never changes the next command cwd.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == "true" ]] || { echo "Isolated GitHub Actions only" >&2; exit 2; }
root="$(git rev-parse --show-toplevel)"
cd "$root"
mkdir -p emulator-evidence
git rev-parse HEAD HEAD^{tree} HEAD^ > emulator-evidence/source.txt
# Only created AFTER emulator boot; a boot failure triggers one environment retry.
touch emulator-evidence/instrumentation-started
cd android
export TZ=Asia/Shanghai
status=1
for attempt in 1 2; do
  set +e
  bash ./gradlew :app:connectedDebugAndroidTest \
    --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process \
    -PallowConnectedDeviceTests=isolated-device \
    > "../emulator-evidence/instrumentation-attempt-${attempt}.log" 2>&1
  status=$?
  set -e
  tail -n 45 "../emulator-evidence/instrumentation-attempt-${attempt}.log"
  if [[ "$status" == 0 ]]; then break; fi
  if [[ "$attempt" -lt 2 ]] && grep -Eq '(Received status code 429|Too Many Requests|HTTP 429)' "../emulator-evidence/instrumentation-attempt-${attempt}.log"; then
    echo "maven_dependency_429_retry" >> ../emulator-evidence/attempts.txt
    sleep 20
    continue
  fi
  exit "$status"
done
if [[ "$status" != 0 ]]; then exit "$status"; fi
# Building AndroidTest APKs or returning 0 without actually running a single
# instrumentation assertion is explicitly NOT a native-test pass.
python3 - <<'PY'
import json
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path("app/build/outputs/androidTest-results/connected")
paths = list(root.rglob("TEST-*.xml")) if root.exists() else []
if not paths:
    raise SystemExit("No device instrumentation JUnit XML; native tests NOT RUN")
totals = {k:0 for k in ("tests","failures","errors","skipped")}
for path in paths:
    suite=ET.parse(path).getroot()
    for key in totals: totals[key] += int(suite.get(key, "0"))
print("API35_NATIVE_COUNTS", totals, "xml_files", len(paths))
Path("../emulator-evidence/native-counts.json").write_text(json.dumps(totals,sort_keys=True)+"\n")
if totals["tests"] < 1 or totals["failures"] or totals["errors"]:
    raise SystemExit("Native tests missing or failed")
PY
