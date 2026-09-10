# Codex handoff — Tesla onboarding + data-source integrity

Date: 2026-09-07
Repository: `Jovifei/tesla-master-mimo`
Branch: `fix/20260907-onboarding-source-integrity`
Base branch: `fix/20260907-cloud-data-recovery`
Base SHA: `bb92f4e840edf3c5f7e1687d40de9e11a8d6cb23`
Primary repair SHA before this handoff: `71e7e5c9ea1a27488735cc780d5f7d7632b3133c`
Draft PR: `#10`

## 1. Product contract

The desired end-user experience is:

1. User taps **Sign in with Tesla** in MateLink.
2. Tesla-owned OAuth UI handles Tesla credentials, MFA and consent. MateLink must never collect or store the user's Tesla account password.
3. MateLink exchanges the Tesla callback for its own session and persists the Tesla refresh grant server-side.
4. The server discovers the user's Tesla vehicles and configures all operator-owned Fleet Telemetry infrastructure automatically.
5. If Tesla requires a virtual key for the vehicle, the user performs the one Tesla-owned confirmation step. This must look like part of onboarding, not like an MQTT / broker / certificate setup task.
6. Once Tesla authorization/key prerequisites are satisfied, Fleet Telemetry streams to MateLink's public backend, the Android app syncs that data into the existing local Room/cache model, and all panels render only observed/derived evidence.

End users must never configure MQTT URLs, usernames, passwords, TLS certificates, topic names, public telemetry ports, command-proxy URLs, CA paths or VIN hash keys. Those are deployment/operator responsibilities.

## 2. Why the old local TeslaMate experience felt simpler

The previous local deployment hid provider and collection infrastructure behind TeslaMate. The user only configured/logged into the local stack; TeslaMate owned the server-side persistence and historical collection path.

The current cloud route intentionally uses Tesla's official Fleet API and Fleet Telemetry. Tesla now separates:

- third-party account authorization/scopes;
- partner/application registration and hosted public key;
- vehicle-level virtual-key trust for Fleet Telemetry / signed vehicle interactions.

The first two are operator/application responsibilities except for Tesla's OAuth consent screen. Vehicle-level key pairing is deliberately user-in-the-loop and cannot be silently bypassed by MateLink.

## 3. Current code route after `71e7e5c`

### OAuth

`deploy/jourvolt-dev-mock/tesla_oauth.go`

- requests `openid`, `offline_access`, `vehicle_device_data`, `vehicle_location`;
- no longer forces `prompt=login` every time;
- uses `prompt_missing_scopes=true`;
- uses `require_requested_scopes=true`;
- uses `show_keypair_step=true` so Tesla can tell the user a virtual-key step may follow.

Do **not** add `vehicle_cmds` merely to make Fleet Telemetry work unless live Tesla evidence or official endpoint requirements prove it is needed. Keep least privilege.

### Live vehicle data

`deploy/jourvolt-dev-mock/fleet_provider.go`

- preferred call: `vehicle_data?endpoints=location_data`;
- if that call fails with authorization but core `vehicle_data` succeeds, keep SOC/range/charging/climate/locks available and mark location permission as required;
- no missing GPS may erase otherwise valid live status.

### Fleet Telemetry

- vehicle discovery registers the vehicle with telemetry storage;
- first safe configuration attempt is triggered server-side with idempotent gating;
- missing virtual key is persisted as `pairing_required`;
- `permission_required` is distinct from pairing and transport failures;
- Android no longer hammers a persisted pairing error every time Data Readiness opens.

### History

- Fleet Telemetry completed drive/charge sessions are authoritative cloud history.
- Room is the phone-side durable cache / offline fallback.
- old `local_import` rows are retained as `incomplete/local_import_summary_only` and remain display-only evidence;
- incomplete imports must not enter analytics and must not produce invented addresses, GPS, routes, speed/power curves or charge curves.

## 4. P0 acceptance gap to resolve locally

`show_keypair_step=true` only tells Tesla that a virtual-key step may immediately follow. It does **not** itself prove the app performs that second step after the OAuth callback.

Before merge, inspect the real login path (`TeslaLoginViewModel`, `TeslaLoginScreen`, navigation, session exchange, Data Readiness) and prove one of these product behaviors:

- Preferred: after successful OAuth/session exchange, MateLink immediately evaluates Tesla pairing for the selected/first vehicle and, only when `pairing_required`, surfaces/launches the official `https://tesla.com/_ak/<partner-domain>` flow as the next onboarding step; after returning, MateLink re-checks pairing/configuration and proceeds automatically.
- Acceptable fallback: a one-time, explicit post-login MateLink screen/dialog explains "Tesla needs one more confirmation" and opens the official Tesla pairing link. The user must not be sent hunting through Settings/Data Readiness to discover it.

Do not auto-launch a browser/app repeatedly on every resume. Persist/gate the onboarding step and retry once after returning from Tesla.

If the real Tesla account/vehicle does not require a virtual key (for example a supported exception reported by `fleet_status`), skip the step.

## 5. Panel data-source matrix

| Surface | Authoritative source | Allowed fallback | Forbidden behavior |
|---|---|---|---|
| Dashboard state / SOC / range / climate / locks | Fleet `vehicle_data`, overlaid by fresh Fleet Telemetry | none for unrelated live fields | one missing permission must not blank the whole panel |
| Vehicle location | Fleet `location_data` or fresh Telemetry `Location` | last **observed** cached coordinate, clearly stale/mixed | inventing GPS or presenting old GPS as fresh |
| Drives | completed Fleet Telemetry sessions + Room cache | incomplete local-import summaries may remain visible | incomplete rows in analytics; fake route/curve |
| Drive route/speed/power | observed telemetry route/sample points | none | interpolating a missing historical trace as measured data |
| Charges | completed Fleet Telemetry charge sessions + Room cache | incomplete local-import summaries may remain visible | incomplete rows in analytics |
| Current charge | open telemetry session, otherwise a live Fleet charge snapshot | none | turning one point into a fake charge curve |
| Charge cost | explicit user total, explicit free policy, or observed provider cost | unavailable | hardcoded tariff or inferred zero/free cost |
| Battery live panel | Fleet/Telemetry SOC/range | eligible history trend | fabricating capacity/health from SOC or range |
| Battery health | measured capacity evidence only | unsupported | fake original/current capacity |
| Range/Efficiency | analysis-eligible observed/derived drive history | stale eligible Room cache | incomplete local imports included |
| Statistics/Timeline | Room aggregates fed from unified eligible history | persisted eligible Room history | dropping provenance / pretending stale is live |
| TPMS | Fleet/Telemetry observed values | persisted observed TPMS samples | missing becomes `0` |
| Data Readiness | per-capability real source | none | labeling telemetry/local history generically as Fleet API |

## 6. High-confidence defects already repaired in branch

- OAuth no longer forces credential entry on every authorization and now requests only missing scopes.
- location-specific authorization failure no longer blanks unrelated live vehicle data.
- first Fleet Telemetry configure attempt is initiated by server-side vehicle discovery.
- repeated Android configure hammering for a persisted missing-key state is blocked.
- returning from the official key flow gets one deliberate configuration retry.
- Data Readiness drive/charge source attribution distinguishes `telemetry_mqtt` and local history.
- Dashboard GPS cache fallback no longer changes the source label of the entire live snapshot; coordinate field provenance becomes `database_latest` and the snapshot becomes mixed-source.
- hardcoded `1.10/kWh` charge-cost estimation is removed; missing cost is unavailable.
- official operator/user boundary and source matrix are documented in `docs/audits/2026-09-07-onboarding-source-integrity.md`.

## 7. Local code audit targets

Review these before adding any new behavior:

- `android/app/src/main/java/com/matelink/ui/screens/auth/TeslaLoginViewModel.kt`
- `android/app/src/main/java/com/matelink/ui/screens/auth/TeslaLoginScreen.kt`
- `android/app/src/main/java/com/matelink/ui/navigation/*`
- `android/app/src/main/java/com/matelink/ui/screens/readiness/*`
- `android/app/src/main/java/com/matelink/ui/screens/dashboard/*`
- `android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt`
- `android/app/src/main/java/com/matelink/domain/analytics/*`
- `deploy/jourvolt-dev-mock/tesla_oauth.go`
- `deploy/jourvolt-dev-mock/fleet_provider.go`
- `deploy/jourvolt-dev-mock/telemetry_service.go`
- `deploy/jourvolt-dev-mock/telemetry_http.go`
- `deploy/jourvolt-dev-mock/readiness.go`

Check for dead remnants after the cost fix (`ESTIMATED` UI states / constant-false branches), but only remove them if source compatibility and tests remain clean.

## 8. Required local gates

Do not treat GitHub remote execution as sufficient Android/device evidence.

### Baseline

```powershell
git fetch --all --prune
git switch fix/20260907-onboarding-source-integrity
git pull --ff-only
git rev-parse HEAD
git status --short
```

Expected remote branch must contain the primary repair `71e7e5c...` plus this handoff commit. Stop on unexpected in-scope modifications or staged files. Unrelated untracked files may be left untouched.

### Go

```powershell
cd deploy/jourvolt-dev-mock
go test ./... -count=1
go vet ./...
go mod verify
go build ./...
```

### Android

Run at minimum:

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:testReleaseUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
.\gradlew.bat :app:lintRelease --no-daemon
```

Record exact test counts and lint error/warning counts. Any new error is a blocker.

### Preserve-data device install

- snapshot package/version/firstInstallTime and configured-state metadata without reading secrets;
- use only same-signature `adb install -r`;
- never uninstall and never `pm clear`;
- prove `firstInstallTime` is unchanged;
- prove the existing MateLink session and settings survive the update;
- exercise populated Dashboard, Drives, Charges, Battery, Range, Efficiency, Statistics, Data Readiness and Settings;
- inspect logcat after interaction for MateLink FATAL/crashes.

### Real Tesla provider closure

After deploying matching backend code:

1. complete Tesla OAuth through Tesla-owned UI;
2. verify vehicle list succeeds;
3. verify core live data independently from location;
4. if location scope is absent, the app must show reauthorization for location without blanking SOC/range/etc.;
5. if virtual key is missing, verify the official key pairing step is surfaced as onboarding, not infrastructure setup;
6. return to MateLink and verify Fleet Telemetry config progresses to `config_synced=true` (or a truthful pending state if the vehicle is asleep);
7. verify the first real MQTT event reaches latest/event-buffer tables;
8. verify real GPS; then one real drive; then one real charge;
9. verify route points and speed/power/charge curves are derived only from received events;
10. only after this evidence may `READY_TO_MERGE` become YES.

## 9. External blocker semantics

A Tesla 401/403 that persists for both location-specific and core `vehicle_data` means the Tesla grant itself is not usable for the requested resource. MateLink may refresh its token and offer Tesla reauthorization, but it must not fake success or attempt to collect Tesla credentials directly.

A Fleet Telemetry `missing_key` result means the vehicle has not accepted the application's virtual key. This is a Tesla-enforced user-in-the-loop trust step. The application can make the UX nearly seamless, but cannot bypass the Tesla confirmation.

## 10. Commit/push discipline

- Preserve `com.matelink` package and user data.
- Never print or commit `.env`, Tesla access/refresh tokens, private keys, VINs, precise coordinates or private addresses.
- Avoid destructive Git.
- Keep the PR draft until Android build/device and real-provider closure are complete.
- If code changes are needed locally, commit only the minimal reviewed files and push to `fix/20260907-onboarding-source-integrity`; update PR #10 rather than opening a competing branch.
