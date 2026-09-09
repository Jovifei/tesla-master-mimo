# Browser choice and post-authorization data review

Date: 2026-09-09. Baseline: `9d49a33`, branch `fix/20260907-onboarding-source-integrity`.

## Scope and evidence

Browser choice is authorized for implementation. Data-chain review is read-only: no server logic, production configuration, vehicle configuration or stored history was changed. Findings below come from source inspection, not a new real-vehicle acceptance run.

The OAuth launcher now queries HTTPS browsers with MATCH_ALL and GET_RESOLVED_FILTER, excluding handlers restricted to specific hosts, then displays an in-app browser list and launches the selected explicit component, rather than automatically opening a Custom Tab in the default browser. The manifest exposes only HTTPS handlers through package visibility. An in-app list avoids the Android 10+ limit of two EXTRA_INITIAL_INTENTS in the system chooser. This is necessary because a read-only query on the connected OnePlus returned only Heytap with default flags, but included Chrome with MATCH_ALL. Existing safe-launch handling reports missing/forbidden external activities. Pending URL consumption remains before launch, so returning/cancelling does not automatically reopen the chooser; an explicit login click obtains a new authorization request. Tesla key deep links remain unchanged. Version: 2.1.10 (29).

## Architecture verdict

Official OAuth, persistent rotating tokens, vehicle key confirmation, signed Telemetry configuration, MQTT ingestion, database aggregation and Android rendering form a reasonable architecture. OAuth alone does not prove data delivery. Pairing, vehicle/firmware support, successful configuration and actual field observations remain necessary. Existing incomplete imported history cannot become measured routes or curves through reauthorization.

## Findings and repair status

1. **Fixed — Stoplights can split drives.** Drive finalization now requires an observed Park/Neutral gear state; a zero-speed observation while still in Drive/Reverse no longer closes the session. The debounce remains applied after the parked/neutral candidate.
2. **Fixed truth boundary — Charging power no longer becomes driving power.** AC/DC charging power is stored only as a charge measurement. Drive route points leave `power` null because the configured Fleet Telemetry field set has no dedicated traction-power field. This prevents a false curve; a measured drive-power source remains a separate product decision.
3. **Partially fixed — Charge measurements are persisted and rendered.** SOC, energy delta, charger power and valid charge location observations are collected into charge points and stored in PostgreSQL. Drive energy remains unavailable for live Telemetry; imported measured summaries remain visible. Tariff, battery health and provider historical backfill remain unavailable without evidence.
4. **Fixed with bounded refresh — Delayed configuration synchronization.** Pairing reads now perform a throttled official Fleet config check for pending vehicle states, and persist `config_synced=true/false` only from that official response. MQTT status alone still cannot claim official configuration.
5. **Fixed — Same-value freshness.** An observation with the same value and a newer timestamp advances the latest observation and session machine; an exact replay at the same or older timestamp remains a duplicate. This aligns with change-based Fleet Telemetry and avoids treating a fresh unchanged field as stale.

## Expected availability

| Data | Conditions / current limit |
| --- | --- |
| Vehicle list and supported current SOC/range/status | Valid OAuth grant, account vehicle access, successful Fleet response |
| GPS and route/velocity samples | Location permission, key/configuration, supported vehicle and real observations |
| New completed drive/charge summaries | Actual start/end events and relevant measurements; drive segmentation now requires Park/Neutral |
| Drive endpoint addresses | Valid real endpoints plus working AMap reverse geocoding |
| Driving power/energy | Live drive power and energy remain unavailable until a dedicated measured source is selected; no charging value is substituted |
| Charge SOC/energy/power/location points | Supported for new events when the corresponding Fleet fields are delivered; address/cost still need separate evidence |
| Historical pre-authorization curves | Not automatically backfilled by Telemetry |
| Battery health and charge cost | Measurement/billing/manual evidence required; authorization alone is insufficient |

## Acceptance still needed

Local verification: Go `test ./... -count=1`, `go vet ./...`, and `go mod verify` pass after the repair. New regression tests cover all five repaired boundaries. Final Debug and Release JVM suites each ran 526 tests, zero failures/errors, with 8 Release skips. Final lintDebug/lintRelease and signed assembleRelease passed. Initial version-contract and compile issues were corrected; a 2 GB Gradle heap failure was recovered with command-local 4 GB heap/two workers. No build configuration change was required. APK identity and signing certificate are recorded in the release ledger. No commit, deployment or installation was performed.

Browser chooser with multiple installed browsers and a default set; select an alternative, cancel and retry, and return via the real App Link. Never enter Tesla credentials in tests. Then independently prove official config sync, first MQTT, GPS, one drive including a traffic stop, and one completed charge against database/API/UI sources and timestamps. No production or device pass is claimed by compilation or contract tests.

References: [Android intents and chooser](https://developer.android.com/guide/components/intents-filters), [Tesla Fleet Telemetry](https://developer.tesla.com/docs/fleet-api/fleet-telemetry), [Tesla OAuth tokens](https://developer.tesla.com/docs/fleet-api/authentication/third-party-tokens).
