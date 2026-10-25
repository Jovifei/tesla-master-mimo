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
# This script only runs against the disposable API35 emulator. Keep its display
# awake for Compose pixel capture after the full native suite has been running.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
  echo "Refusing screenshot setup on a non-emulator" >&2; exit 2;
}
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
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
  # Keep a failing synthetic Compose capture for diagnosis, while the native
  # assertion remains red. Missing files are expected for earlier failures.
  for shot in matelink_fixture_parked_full matelink_fixture_parked_partial; do
    adb pull "/sdcard/Download/${shot}.png" "../emulator-evidence/${shot}.png" >/dev/null 2>&1 || true
    adb pull "/sdcard/Download/${shot}.proof" "../emulator-evidence/${shot}.proof" >/dev/null 2>&1 || true
  done
  exit "$status"
done
if [[ "$status" != 0 ]]; then exit "$status"; fi
# Pull only screenshots captured by the explicit synthetic fixture native test.
# Never screenshot a connected phone or a real user address into artifacts.
for shot in matelink_fixture_parked_full matelink_fixture_parked_partial; do
  adb pull "/sdcard/Download/${shot}.png" "../emulator-evidence/${shot}.png"
  adb pull "/sdcard/Download/${shot}.proof" "../emulator-evidence/${shot}.proof"
done
python3 - <<'PYSCREEN'
from pathlib import Path
import hashlib
import struct
digests=[]
for name in ('matelink_fixture_parked_full','matelink_fixture_parked_partial'):
    p=Path('../emulator-evidence')/(name+'.png')
    data=p.read_bytes()
    if len(data)<10000 or data[:8]!=b'\x89PNG\r\n\x1a\n':
        raise SystemExit('Missing real emulator PNG: '+name)
    width,height=struct.unpack('>II',data[16:24])
    if width<320 or height<480:
        raise SystemExit('Invalid emulator viewport: '+name)
    proof=(p.with_suffix('.proof')).read_text().split()
    if len(proof)!=5:
        raise SystemExit('Missing Compose pixel proof: '+name)
    digest=hashlib.sha256(data).hexdigest()
    if digest!=proof[0] or (width,height)!=(int(proof[1]),int(proof[2])):
        raise SystemExit('Screenshot pixels do not match tested Compose root: '+name)
    dark,bright=int(proof[3]),int(proof[4])
    if dark<=30 or bright<=30:
        raise SystemExit('Screenshot is blank/black and cannot qualify UI: '+name)
    digests.append(digest)
    print('API35_COMPOSE_SCREENSHOT',name,width,height,len(data),dark,bright)
if digests[0]==digests[1]:
    raise SystemExit('Full and partial parked UI screenshots are identical')
PYSCREEN
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
