# Android Core UX & State Reliability Repair

Baseline: `main@11ba77d3206828ce4dc745d03dbde7f21eeb0201`

This repair is intentionally limited to Android connection/session restoration, live-state truthfulness, charging-state freshness, supported Settings controls, and second-launch locale behavior.

## Changes

- Persisted connection mode is authoritative after the one-time legacy inference; stale self-hosted settings or a surviving cloud session no longer silently switch modes on restart.
- JourVolt refresh failures clear the local session only for explicit 401/403 authentication rejection. Network/server/configuration failures preserve credentials for retry.
- Tesla re-authorization is non-destructive: the existing session remains usable until a replacement authorization succeeds.
- Dashboard polling is guarded against car-switch races and distinguishes live, recent, historical, mixed, and unavailable evidence.
- Dashboard legacy fallback clears MQTT-only metadata; complete polling failure marks evidence unavailable instead of presenting stale values as live.
- Dashboard distance, range, temperature, and driving-power presentation use consistent unit semantics.
- Current Charge reads Adapter/Fleet evidence first, falls back to TeslaMate status, and clears instantaneous voltage/current/phase values when no current snapshot is available.
- Current Charge reports sample insufficiency instead of rendering empty chart cards.
- Self-hosted Settings exposes only the supported server root + API Key path; legacy multi-server/Basic Auth/invalid-certificate controls remain stored for compatibility but are not presented as functioning controls.
- Persisted locale is applied before the first Activity composition to avoid second-launch language flash.

## Verification gates

- `git diff --check`
- XML resource parsing
- pure Kotlin compilation for `SnapshotEvidence`
- Android Debug JVM tests (477/477 passed)
- Debug APK and AndroidTest APK assembly
- Android Debug lint

Device validation is still required for real Tesla cloud refresh failure, cloud/self-hosted switching, MQTT freshness transitions, and active AC/DC charging.
