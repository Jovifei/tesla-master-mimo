# P0 + P0.5 Android Device Qualification — 2026-07-17

## Conclusion

**FAIL (P0.6 automation stop on 2026-07-18)**

The 2026-07-17 candidate result below remains a historical conditional pass.
The initial P0.6 manual-witness follow-up failed at the normal connection test.
After Jovi explicitly authorized a minimal remediation, the replacement APK
passed its real-device connection test. The broader failure-state, navigation,
and public-link cases remain separately qualified as below.

The later attempt to run the existing connected Android test caused the target
package to no longer be present on the device. This violates the P0.6 rule that
the App must not be uninstalled, so qualification is failed and all further
device work is stopped pending Jovi's direction.

The candidate preserves the existing encrypted connection configuration across a
cover install, restores the real Dashboard, and is stable for the exercised
cold-start, manual-refresh, and foreground/background cycles.  The remaining
conditions are explicit: the connected device did not expose Compose page text
or control semantics to the safe UI-automation path, so configuration failure
states, Drives/parked detail/Charges navigation, and the three public-link
clicks were not claimed as device-verified.

No source, configuration, Docker, network, server data, or device configuration
was changed during the original qualification. The later, explicitly authorized
P0.6 remediation is recorded in its own addendum below.

## Environment and candidate

| Item | Evidence |
| --- | --- |
| Branch / HEAD | `codex/app-mimo-data-setup` / `7f01e4d1c7759700aa4884fb7db225397e68019d` |
| Upstream relation | behind 0, ahead 1 at preflight |
| Candidate | `com.matelink`, version `1.0.0` (code 1) |
| APK SHA-256 | `1779A0984DD1FE9FB3A55D7F842DCE2EC0D0C975708590454E6367A3C585F8C0` |
| Device summary | one authorized Android 11 device; 1440 × 3120 display |
| Services | database, adapter, broker, TeslaMate/API, and Grafana containers reported running; no service action taken |
| Local regression | `:app:testDebugUnitTest` exited successfully |

The adapter capability endpoint is authenticated.  It was not queried with a
real credential outside the App.

## Device test matrix

| Scope | Result | Evidence / limitation |
| --- | --- | --- |
| Cover install | PASS | `adb install -r` succeeded; no data clear or uninstall was used. |
| Existing address + encrypted API Key survive | PASS | Candidate cold-launched to the real Dashboard without re-entering or saving credentials. |
| Dashboard real data | PASS | A real vehicle state rendered after the cover install; no values or location were retained. |
| Drives | CONDITIONAL | App navigation control was exercised, but safe automation could not obtain a semantic page identity; do not claim the list as fully verified. |
| Parked detail | NOT VERIFIED | Requires the Drives list item path; not guessed after the semantic-automation limitation. |
| Charges | CONDITIONAL | Navigation control was exercised, but page identity/data was not safely asserted. |
| Cold start | PASS (20/20) | Each cycle force-stopped the App, cold-started `MainActivity`, and confirmed the process. |
| Dashboard manual refresh | PASS (10/10) | Ten top-bar refresh actions retained a live process; scoped logs had no crash, HTTP 401, or data-parse anomaly. |
| Background / return | CONDITIONAL PASS (5/5) | Five Home/return cycles retained the process and scheduler contained App/WorkManager markers.  A worker execution window was not independently observed. |
| Wrong API Root / wrong API Key / unreachable root | NOT VERIFIED | Temporary UI entry was not attempted because settings field semantics could not be located without inspecting saved configuration content. No failed value was saved. |
| No-data degradation | NOT VERIFIED | Depends on the same temporary configuration flow; no server or App data was altered. |
| URL normalization / local HTTP warning / public HTTP block | PASS in unit regression; NOT VERIFIED on device | Existing unit suite passed; device UI assertion remains pending. |
| API Key masking and non-persistence of Test | CONDITIONAL | Cover-install recovery is positive evidence. Direct field/semantics inspection was intentionally not performed. |
| Help / legal / changelog external links | NOT VERIFIED | Public pages exist in the candidate tree, but the About-page click path was not safely asserted on device. |

## Safety and log evidence

- No real API Key, Authorization header, server address, location, VIN,
  personal name, or device serial was printed or placed in this report.
- During the ten-refresh scoped capture: **0** `FATAL EXCEPTION`, **0** HTTP
  401, and **0** JSON/data-parse anomaly markers.
- No global log buffer counts are used as acceptance evidence because they may
  predate this run.
- The temporary device screenshot used only for the initial Dashboard visual
  check is outside the repository and is not an acceptance artifact.

## Qualification block

The device's Compose accessibility export did not expose page labels or the
settings controls through the safe UI-automation query.  Continuing by reading
the settings UI hierarchy would risk collecting the saved connection address or
credential representation, which is outside this qualification's privacy
boundary.

Minimal follow-up: run the remaining configuration/error and public-link cases
with an approved on-screen manual witness, or add an approved, non-secret
instrumentation-only route that exposes stable test tags without serializing
field values.  The relevant UI files are
`android/app/src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt`
and `android/app/src/main/java/com/matelink/ui/screens/about/AboutScreen.kt`.

## Candidate commit boundary

Do **not** stage or commit the current worktree as-is.  It contains pre-existing
mixed P0 work, deployment changes, task notes, generated `android/.kotlin/`
state, and unrelated untracked material.  The intended P0.5 partition requires
a deliberate diff review around:

- connection validation/security and persistence: `UrlSecurity.kt`,
  `ConnectionTestResult.kt`, `SettingsDataStore.kt`, `NetworkModule.kt`;
- connection UI: `SettingsScreen.kt`, `SettingsViewModel.kt`, localized
  `strings.xml` files;
- public information entry: `AboutScreen.kt`, `PublicInfoLinks.kt`, manifest or
  build configuration supporting it;
- focused tests: URL/security, connection-result, settings, and public-link
  tests; and
- static public pages under `web_matelink/public/`.

`android/.kotlin/` and the database schema artifact are generated/untracked
state and are explicitly outside any candidate commit.  This report is review
evidence, not product scope.

## One next action

Obtain a single approved manual-witness pass through Settings and About to
complete the seven unverified P0/P0.5 cases, then reassess this same candidate
for a clean, deliberately partitioned commit.

## P0.6 Manual-Witness Follow-up — 2026-07-18

### Baseline

- Branch: `codex/app-mimo-data-setup`; HEAD: `7f01e4d1c7759700aa4884fb7db225397e68019d`.
- Candidate APK SHA-256 matched the installed package.
- Installed package: `com.matelink`, version `1.0.0`, code `1`.
- One authorized Android device was connected; no device identifier is retained
  in this report.
- No source, test, database, Docker, network, `.env`, or Git-index changes were
  made. Mock mode was switched off manually on the device to enable the test
  control; no connection value was saved by Codex.

### Settings witness

| Item | Result | Evidence |
| --- | --- | --- |
| Server address field populated | PASS | Jovi confirmed content was present; the value is not recorded. |
| API token field populated and masked | PASS | Jovi confirmed content was present; only masked dots were visible. |
| Test Connection initially available | FAIL | The control was grey while Mock mode was enabled and became available only after Mock mode was switched off. |
| Normal connection test | FAIL | With the current populated values, the visible result reported host connection failure. |
| Independent Save Configuration control | FAIL | The visible action was labelled `Save`; no separate `Save Configuration` label was witnessed. |

The invalid API Key, invalid API Root, local URL rejection, and public HTTP
blocking cases were not executed after the normal connection failure. No
dependent Drives, parked-detail, no-data, or About-link claims were made.

### Redacted log review

The bounded post-test log window was inspected by counts only; raw lines were
not retained or displayed.

- `FATAL EXCEPTION`: 0
- `ANR`: 0
- HTTP 401 markers: 0
- Authentication/header/token value matches: 0
- MateLink process remained alive after the test.

### Runtime correlation after repeated 401

With Mock mode off, Jovi cleared and re-pasted the API token, then repeated
Test Connection. The visible result was still HTTP 401. No value was saved.

A read-only local control check found the Adapter running, the non-empty
deployment token matching the Adapter's running token, and an authenticated
request to its local capabilities endpoint returning HTTP 200. Token values
were neither printed nor retained. Together with the earlier device evidence
of requests reaching the configured Adapter root with a redacted Authorization
header, this rules out a stopped Docker stack, an unapplied server token, and
an unavailable local Adapter endpoint.

The remaining evidence shows that the backend rejects the token value actually
sent from the phone, but the masked Settings field and redacted transport logs
cannot safely disclose or compare that value. The exact mismatch cause is
therefore unproven within this qualification boundary.

### Qualification decision

**FAIL.** The normal populated configuration did not complete a successful
connection test, and the witnessed Settings action surface does not match the
P0.6 requirement for an independently enabled test action and explicit save
configuration action. The remaining scenarios are **BLOCKED** by this
foundational failure, not treated as passing by inference.

### Original minimal repair boundary

The next code review, only after separate Jovi authorization, should first add
a controlled, non-secret way to correlate the token submitted by Settings with
the server-side expectation, then reconcile the Settings action surface. The
current source locations are
`android/app/src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt`
and `android/app/src/main/java/com/matelink/ui/screens/settings/SettingsViewModel.kt`.
At that point, no network, data-model, or UI implementation change was
authorized by the report update.

### Authorized remediation and scripted rerun

Jovi later authorized a minimal authentication-path change. The App now trims
only leading and trailing whitespace from the API token immediately before it
forms the cache key and Authorization header. It does not alter token contents,
display, persistence, Docker, or `.env`. The changed source/test scope is
limited to `android/app/src/main/java/com/matelink/di/NetworkModule.kt` and
`android/app/src/test/java/com/matelink/di/NetworkModuleTest.kt`.

The remediation debug APK was cover-installed without uninstalling or clearing
device data. Jovi then confirmed a successful Test Connection with Mock mode
off. A bounded, redacted device-log review found HTTP 200 markers, no new HTTP
401 marker, and no fatal exception or ANR; no Authorization value was retained.

Automated verification then completed without further manual input:

- Full `:app:testDebugUnitTest`: PASS, including URL validation cases that
  reject malformed roots before a request and block public HTTP.
- Adapter black-box contract: current token HTTP 200; invalid and absent tokens
  HTTP 401.
- Device boundary: a temporary invalid token returned HTTP 401; restoring the
  token returned a successful connection. No invalid value was saved.

The earlier attempt to enter `https://` in the device field reported a timeout
rather than the expected local validation message. The automated URL-validation
test passes, but that specific rendered-UI message is not claimed as witnessed.

### Updated P0.6 decision

**CONDITIONAL PASS.** The authentication failure is resolved on the replacement
APK and is covered by unit, Adapter, and real-device evidence. Remaining
conditions are limited to the pre-existing unverified UI/navigation and public
link cases, plus the exact rendered malformed-root message. No clean candidate
commit boundary review is performed while the worktree remains intentionally
mixed.

### Automation stop and superseding decision

The existing `:app:connectedDebugAndroidTest` task completed its static
Settings Compose test, but a subsequent package check found no installed
`com.matelink` package. No further device command, reinstallation, or
configuration entry was performed. Since the P0.6 scope explicitly prohibits
uninstalling the App, this condition supersedes the earlier conditional result.

**FAIL.** The App must be restored only with Jovi's explicit direction; the
prior encrypted configuration cannot be claimed recoverable. Candidate commit
boundary review is not eligible.

### Post-stop restoration and script-only evidence

Jovi explicitly authorized a fresh installation of the verified MateLink debug
APK after the package-loss stop. The package and launcher were then present and
the App process started normally. Jovi re-entered a valid configuration. A
scripted force-stop/restart subsequently found the MateLink Activity resumed,
an HTTP 200 marker, no HTTP 401 marker, no fatal exception or ANR, and no
unredacted Authorization value in the bounded log window.

Read-only Adapter checks found real car, drive, charge, and at least one parked
detail candidate response available. These prove backend data availability,
not App navigation or rendered-detail behavior. A future-range query did not
produce an empty response, so no-data UI behavior remains unverified; no data,
database, Docker, or network state was modified to force it.

The final decision remains **FAIL**: the earlier target-package loss prevents
claiming that the prior encrypted configuration survived, and the remaining UI
interaction cases were not safely automated. No candidate boundary review was
performed.
