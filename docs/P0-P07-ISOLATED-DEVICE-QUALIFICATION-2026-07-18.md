# P0.7 Isolated Android Qualification Report

Date: 2026-07-18
Workspace: `E:\project\tesla_master\app_mimo`
Branch: `codex/app-mimo-data-setup`
HEAD: `7f01e4d1c7759700aa4884fb7db225397e68019d`

## Final result

**FAIL**

The isolated Android qualification environment is now usable and the protected emulator path passed the stable synthetic suite. The P0.7 candidate cannot be promoted because the remaining strict P0.6 interaction matrix is not fully closed: parked detail rendering was not proven in the full app flow, no-data UI was not proven, and several Settings error-state interactions remain JVM/static-test covered rather than full UI automated.

No candidate commit boundary review was performed because the final result is not PASS.

## Scope and safety boundary

- No real phone was used for qualification execution.
- All device commands used explicit serial `emulator-5554`.
- Physical-device guard rejects non-emulator serials and requires `ro.kernel.qemu=1`.
- No `.env`, Docker, real database, real service data, git add, commit, push, reset, restore, checkout, clean, or stash was used.
- Production source was not changed in this P0.7 pass.

## Isolated Android qualification environment

Dedicated AVD:

- Name: `MateLink_P0_Qualification_API35`
- Serial used: `emulator-5554`
- Android: 15 / API 35
- Image: `system-images;android-35;google_apis;x86_64`
- Display: `1080x2400`
- Emulator proof: `ro.kernel.qemu=1`, `sys.boot_completed=1`

Android command-line tools were installed from the official Android command-line tools package into the local Android SDK. The downloaded package was stored under the allowed download path:

- `E:\Claude_allow\Download\commandlinetools-win-14742923_latest.zip`
- Local SHA-256: `CC610CCBE83FADDB58E1AA68E8FC8743BB30AA5E83577ECEB4CC168DAE95F9EE`

## Guardrails added

- `tools/p0_qualification/assert_emulator_only.ps1`
  - Requires an explicit `-AndroidSerial`.
  - Rejects serials not matching `emulator-*`.
  - Checks that the attached device reports `ro.kernel.qemu=1`.
  - Hash: `2E86075092B3AE24A066C8197DD9A5070DA9FA4DCB9018F5DF7A45C741112837`
- `tools/p0_qualification/run_guarded_instrumentation.ps1`
  - Runs instrumentation only after the emulator guard passes.
- `tools/p0_qualification/fixture_server.py`
  - Synthetic-only local fixture server.
  - Supports normal, empty, missing-parked, and timeout scenarios.
  - Does not log Authorization values.
  - Hash: `DBBD9863085C0E99D79BFC8B899BD1391CCA139EA6B2B034E30613BDBA9995A1`
- `tools/p0_qualification/start_fixture_server.ps1`
  - Starts the fixture server and health-checks it.

The physical phone serial observed earlier was rejected by the guard as expected. That proved the new harness fails closed before any real-device execution.

## APK identity

Baseline APK, built from `HEAD` archive:

- Path: `E:\temp\matelink-p0-baseline-7f01e4d-zip\android\app\build\outputs\apk\debug\app-debug.apk`
- Package: `com.matelink`
- Version: `versionCode=1`, `versionName=1.0.0`
- Min SDK: 26
- Target SDK: 35
- Launcher: `com.matelink.MainActivity`
- SHA-256: `BB16389F20BBE2E8746F96ADA2D5C8D7BF69981C11E69E99E523FF10EA452808`

Candidate APK:

- Path: `E:\project\tesla_master\app_mimo\android\app\build\outputs\apk\debug\app-debug.apk`
- Package: `com.matelink`
- Version: `versionCode=1`, `versionName=1.0.0`
- Min SDK: 26
- Target SDK: 35
- Launcher: `com.matelink.MainActivity`
- SHA-256: `0758151B5F16F656D732B638D2E550707C9929F3713FA91C2EB2D8719A50C509`

Candidate androidTest APK:

- Path: `E:\project\tesla_master\app_mimo\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`
- Package: `com.matelink.test`
- Target package: `com.matelink`
- SHA-256: `3FD7BCF3884A27E49657084BFA3C4FE00AEC8A6D304509B7F5078ECDAE856D2F`

## Package disappearance review

The P0.6 package-disappearance symptom was not reproduced on the isolated emulator:

- Installing target APK plus androidTest APK preserved both packages.
- Direct guarded instrumentation preserved both packages.
- Baseline-to-candidate upgrade preserved both packages.
- Repository search found no project-owned `adb uninstall`, `clearPackageData`, Android Test Orchestrator, `uninstallAll`, or scripted uninstall path relevant to normal test execution.

The Gradle `connectedDebugAndroidTest` path failed locally before test execution with a UTP gRPC server startup failure. After that failure, both `com.matelink` and `com.matelink.test` were still installed.

Conclusion: the old disappearance was an environment/qualification-path failure, not a proven product-code defect. The exact uninstall actor was not reproduced. The practical mitigation is to use the new emulator-only guard plus direct instrumentation instead of ambiguous device selection or the currently broken Gradle connected-test path.

## Upgrade and retained configuration evidence

Sequence executed on `emulator-5554` only:

1. Guard passed for `emulator-5554`.
2. Removed prior emulator-only target/test packages.
3. Installed baseline APK.
4. Installed current androidTest APK.
5. Ran `QualificationStorageTest#seedSyntheticConnectionForUpgrade` with `-e p0.storage true`.
6. Installed candidate APK with `adb install -r`.
7. Ran `QualificationStorageTest#verifySyntheticConnectionSurvivedUpgrade` with `-e p0.storage true`.
8. Confirmed `pm path com.matelink` and `pm path com.matelink.test` both returned installed package paths.

Result:

- Address retention: **PASS**
- Encrypted token retention: **PASS**
- Target package retained after upgrade: **PASS**

## Automated test evidence

JVM unit tests:

- `:app:testDebugUnitTest`
- Result from XML: `46 tests`, `0 failures`, `0 errors`, `0 skipped`

Direct guarded instrumentation:

- Command path: `adb -s emulator-5554 shell am instrument ...`
- Stable suite result: `OK (6 tests)`
- Normal stable suite included:
  - Full app synthetic smoke: Dashboard, Drives, Charges
  - Settings two-field/safe-control structure
  - Settings Test and Save callback separation
  - About unconfigured public links
  - Storage tests skipped unless `-e p0.storage true`, by design

Dedicated upgrade instrumentation:

- `QualificationStorageTest#seedSyntheticConnectionForUpgrade`: `OK (1 test)`
- `QualificationStorageTest#verifySyntheticConnectionSurvivedUpgrade`: `OK (1 test)`

Gradle connected instrumentation:

- `connectedDebugAndroidTest`: **BLOCKED**
- Reason: local Android Gradle Plugin / UTP gRPC listener startup failure before tests ran.
- Package disappearance was not reproduced by this failure.

## Interaction matrix

| Area | Result | Evidence / gap |
| --- | --- | --- |
| Isolated AVD creation | PASS | Dedicated API 35 emulator booted and guarded. |
| Real-phone exclusion | PASS | Guard requires explicit emulator serial and rejects physical serials. |
| Baseline APK identity | PASS | Package/version/SDK/hash recorded. |
| Candidate APK identity | PASS | Package/version/SDK/hash recorded. |
| Baseline to candidate upgrade | PASS | Address and encrypted token survived `adb install -r`. |
| Package disappearance | CONDITIONAL PASS | Not reproduced; product defect not proven; exact old actor unknown. |
| Settings two-field shape | PASS | Static Compose instrumentation asserts only server address and API key inputs. |
| Settings Test vs Save separation | PASS | Static Compose instrumentation asserts callbacks are independent. |
| Invalid URL handling | PARTIAL | JVM validation covered; full Settings UI error flow not closed. |
| Public HTTP rejection | PARTIAL | JVM validation covered; full Settings UI error flow not closed. |
| LAN/emulator-host HTTP | PASS | Full app synthetic smoke reached fixture via `http://10.0.2.2:18080`. |
| Wrong key / HTTP 401 | PARTIAL | Fixture supports auth failure path, but full Settings UI interaction was not closed. |
| Wrong address / timeout | PARTIAL | Fixture supports timeout path, but full Settings UI interaction was not closed. |
| About unconfigured links | PASS | Clicks do not open URI and show unconfigured message. |
| Dashboard synthetic data | PASS | Full app smoke displayed fixture vehicle data. |
| Drives synthetic data | PASS | Full app smoke displayed fixture drive data. |
| Charges synthetic data | PASS | Full app smoke displayed fixture charge data. |
| Parked detail | FAIL | Synthetic parked data exists, but the full app test did not prove parked detail rendering without production/testability changes. |
| No-data UI | FAIL | Empty fixture scenario exists, but the no-data UI was not fully automated/proven. |
| Secret/log leak | PASS with note | No full synthetic token, Bearer value, user-info URL, or unredacted Authorization value found. OkHttp emitted redacted `Authorization: ██` markers only. |
| Crash/ANR in clean stable window | PASS | No `FATAL EXCEPTION` or `ANR`. `AndroidRuntime` entries were benign shell runtime start/exit lines. |

## Logcat scan

After clearing logcat and rerunning the stable instrumentation suite:

- `FATAL EXCEPTION`: 0
- `ANR`: 0
- Full synthetic token value: 0
- `Bearer`: 0
- `Authorization: synthetic`: 0
- `Authorization: Bearer`: 0
- `user-info`: 0
- Redacted authorization marker: present as `Authorization: ██`
- `token=` hits were Android system WindowContainerToken/task-transition metadata, not MateLink credential material.

## Files added or changed in the authorized P0.7 surface

- `android/app/src/androidTest/java/com/matelink/p0/QualificationFullAppSmokeTest.kt`
- `android/app/src/androidTest/java/com/matelink/p0/QualificationStorageTest.kt`
- `android/app/src/androidTest/java/com/matelink/ui/screens/about/AboutScreenTest.kt`
- `android/app/src/androidTest/java/com/matelink/ui/screens/settings/SettingsScreenTest.kt`
- `tools/p0_qualification/assert_emulator_only.ps1`
- `tools/p0_qualification/run_guarded_instrumentation.ps1`
- `tools/p0_qualification/fixture_server.py`
- `tools/p0_qualification/start_fixture_server.ps1`
- `docs/P0-P07-ISOLATED-DEVICE-QUALIFICATION-2026-07-18.md`

## Remaining blockers

1. Parked detail full-app rendering is not proven in automation.
2. Empty/no-data UI is not proven in automation.
3. Settings full UI error-state flows for 401, timeout, invalid URL, and public HTTP are only partially covered.
4. Gradle `connectedDebugAndroidTest` is blocked by the local UTP gRPC startup failure; guarded direct instrumentation is the current reliable path.

## Next step

Request a narrow follow-up authorization package for testability-only or minimal production-supported hooks to close:

- parked detail entry visibility/navigation;
- empty/no-data screen states;
- Settings full UI error-state assertions;
- optionally Gradle UTP replacement or continued use of direct guarded instrumentation as the official P0 path.

Until those are closed, P0.7 remains **FAIL**, not a release candidate PASS.
