# App Mimo Bug Repair Report - 2026-07-11

## Root Causes

1. Charge and drive headers rendered start/end values as separate large blocks.
2. Parked rows had no route, ViewModel, detail screen, or server endpoint.
3. Drive energy was not persisted with a source and coverage marker.
4. TeslaMateApi lacks the required live snapshot in this deployment, while the
   dashboard converted missing values to misleading zero/unlocked values.
5. The phone has the Adapter URL saved but no valid API token, so it receives 401.

## Implemented

- Added `deploy/teslamate-home-docker/adapter` with capability, vehicle snapshot,
  parked-detail, and legacy proxy endpoints.
- Added Postgres fallback for battery, range, odometer, location, temperature,
  climate and TPMS, including source and observation time.
- Added parked-period battery, sampled energy, power, temperature and coverage.
- Added Android Adapter integration and Adapter-first dashboard loading.
- Dashboard labels database fallback as `历史快照` and no longer invents zero data.
- Added compact same-line time ranges; same-year end timestamps omit the year.
- Added parked detail navigation/screen and taller drive/parked history cards.
- Added drive energy source/coverage persistence, Room migration 12 to 13, and
  history energy plus average-consumption display.

## Verification

- `go test ./...`: passed.
- `docker compose config --quiet`: passed; all six services are running.
- Real snapshot returned 59%, about 249.75 km range, 15769.68 km odometer and
  TPMS 2.8/2.9/2.9/3.0 bar.
- Parked query for drives 11-12 returned three samples, -1% battery and about 78%
  coverage.
- `:app:testDebugUnitTest :app:assembleDebug`: passed.
- Debug APK installed and launched on device `6e4fa92f` without a MateLink fatal
  exception.

## Final Device Check

The device still receives HTTP 401 because no valid API token is saved. Enter the
ignored `.env` value `MATE_LINK_API_TOKEN` in Settings > Advanced Network > API
Token, run Test Connection, then Save. Verify 59%, about 250 km, all four TPMS
values, and a parked-detail row.

## Remaining Boundaries

- This Adapter version reads latest-position data from Postgres. It does not yet
  persist retained MQTT state, so lock/door/plug can remain unavailable.
- Maps require the user's AMap key. iOS and widgets require macOS/Xcode.
- Existing locale, icon and Moshi Kapt deprecation warnings do not block the build.
