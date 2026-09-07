# CLOUD_DATA_RECOVERY_LOCAL_GATE_REPORT

Date: 2026-09-07

## Git

- Baseline: `db35155b80e12b9a1e641e8181dbeac79ba2ac5d`
- Repair: `847f02b362ab5c1608592af90bf76dbc286f31c5`
- Follow-up migration: `eed9a0b`
- Branch: `fix/20260907-cloud-data-recovery`
- Temporary CI/patch scripts were removed from the branch tip.

## Local gates

- Go: `go test ./... -count=1`, `go vet ./...`, `go mod verify`, and `go build ./...` passed.
- Android Debug JVM: 517 tests, 0 failures, 0 errors, 0 skipped.
- Android Release JVM: 517 tests, 0 failures, 0 errors, 8 skipped.
- `assembleDebug`, `assembleRelease`, and `assembleDebugAndroidTest` passed.
- Debug lint: 0 errors, 264 warnings, 9 informational findings.
- Release lint: 0 errors, 242 warnings, 8 informational findings.

## Device update

- Device: OnePlus 7 Pro `6e4fa92f`.
- Package: `com.matelink`, version `2.1.6`, build `25`.
- APK SHA-256: `F08C5F8E6BF6A8809F3C046641247D2F0733F014E461BEB9737595D58E9EC4C1`.
- Signature matched the installed package; `adb install -r` returned `Success`.
- `firstInstallTime` remained `2026-08-31 22:36:47`; no uninstall or data clear was performed.
- Cold start remained in the existing account/vehicle UI with no MateLink FATAL. This proves package-data preservation, not a valid Tesla provider session.

## Server and database

- ECS `/healthz`: HTTP 200, `mode=fleet`, PostgreSQL persistence, `mock_history=false`.
- ECS `/readyz`: HTTP 200, telemetry `awaiting_first_event`.
- Before migration: 68 `local_import` sessions with prior local-import quality labels.
- After migration: 68 records retained as `incomplete/local_import_summary_only`; no deletion.
- Telemetry pairing/latest/event-buffer/route-point rows: 0.
- Database backup: `/home/jourvolt/backups/jourvolt-postgres-pre-eed9a0b-20260907T082900Z.dump`, SHA-256 `3b7be9bb1bfe7f11eeb7951c9ceba8ed8010fbd469758ea752c65ace45e730ee`.
- Code backup: `/home/jourvolt/backups/jourvolt-pilot-code-pre-eed9a0b-20260907T082900Z.tar.gz`, SHA-256 `32a775a9eb9b7b10ecf4e515ce9aff402efcd32f81ee825732ae90e770e297e8`.
- Build SHA endpoint is `unknown` because the existing Docker build injects `unknown`; deployment identity is tracked by the Git branch/commit above.

## Provider and pages

- Fleet location request path now includes `endpoints=location_data`; unit coverage passed.
- Actual production Fleet requests currently return HTTP 403 `reauthorization`; no location payload was returned.
- Dashboard correctly renders unavailable/waiting GPS and does not invent coordinates.
- Old imported summaries have no route points, coordinates, addresses, or speed/power samples; no curve or address is generated.
- Battery health remains explicitly unsupported without measured capacity. Cached/live SOC and range cards are not proof of current provider freshness.

## Telemetry UX

- Auto-configure policy unit tests passed: `pairing_required` with no provider error triggers the existing configure action.
- Actual auto-configure: NOT VERIFIED; the production pairing table is empty and provider authorization is currently 403.
- Virtual-key page: NOT OPENED.
- `config_synced`: NOT VERIFIED.
- First MQTT event: NO.

## Blockers

1. Reauthorize the Tesla account/session or grant the required Fleet API permission; current provider calls are 403 `reauthorization`.
2. After authorization succeeds, open Data Readiness once so the automatic configure policy can run, then complete any Tesla-official confirmation it presents.
3. Produce one real drive and one real charge before claiming GPS, route, curve, charging-history, or battery-trend recovery.

## Final

- `READY_TO_MERGE = NO` until the provider 403 is resolved and real-event acceptance is completed.
- No `.env` was printed or changed.
- No VIN, token, precise coordinate, or address was included.
- No historical, GPS, capacity, drive-curve, or charge-curve data was fabricated.
