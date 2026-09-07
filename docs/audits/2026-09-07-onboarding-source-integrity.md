# Tesla MateLink onboarding & data-source integrity audit — 2026-09-07

## Product contract

MateLink never asks an end user to configure MQTT, broker credentials, TLS certificates or topics. The user-facing contract is Tesla-hosted OAuth, followed by a Tesla-hosted virtual-key confirmation only when Tesla requires it for that vehicle. Server-side Fleet Telemetry setup and retries are application/operator responsibilities.

An application update must preserve MateLink session, Room history and user settings. `adb install -r` is an update; uninstall / `pm clear` are not permitted for upgrade qualification.

## Source matrix

| Surface | Authoritative source | Fallback | Missing-data rule |
|---|---|---|---|
| Dashboard live state / SOC / range / climate / locks | Tesla Fleet `vehicle_data`, field-overlaid by fresh Fleet Telemetry | last persisted position only for coordinates | location permission failure must not blank unrelated live fields |
| Current vehicle location | Fleet `location_data` or fresh Fleet Telemetry `Location` | last persisted observed coordinates, visibly mixed-source | no invented GPS |
| Drives list | Fleet Telemetry completed sessions + Room cache | incomplete `local_import` summaries remain visible | incomplete imports excluded from analytics; no invented route/curve |
| Drive route / speed / power curves | observed telemetry route/sample points | none | summary-only imports show unavailable |
| Charges list | Fleet Telemetry completed charge sessions + Room cache | incomplete `local_import` summaries remain visible | incomplete imports excluded from analytics |
| Current charge | open Telemetry charge session, else Fleet live charge snapshot | none | one live point is not a fabricated curve |
| Charge cost | explicit user total/free policy/observed provider cost | none | no hardcoded tariff estimate |
| Battery live panel | Fleet/Telemetry SOC and range | eligible history trend | capacity/health stays unsupported without measured capacity |
| Range / efficiency analytics | analysis-eligible observed/derived drives | stale eligible Room history | incomplete imports excluded |
| Statistics / timeline | Room aggregates fed from unified history | persisted eligible Room history | preserve provenance and stale state |
| TPMS | Fleet live / Telemetry + local observed samples | persisted observed samples | missing != zero |
| Data readiness | per-capability source | none | drive/charge source must reflect Telemetry/local history, not generic Fleet API |

## High-confidence defects fixed in this branch

1. OAuth always forced `prompt=login`; it now requests only missing scopes and asks Tesla to show the key-pair step during Tesla-hosted onboarding.
2. `vehicle_data?endpoints=location_data` 403 previously blanked SOC/range/charging/climate too; core vehicle data now falls back independently while location remains permission-required.
3. Fleet Telemetry first configuration depended on visiting a diagnostic page; vehicle discovery now triggers one idempotent server-side initial attempt.
4. Android repeatedly retried a persisted missing-key state on every Data Readiness visit; automatic retry is now initial-only, with one deliberate retry after returning from the official Tesla key flow.
5. Data Readiness labeled drive/charge history with the live Fleet provider source; it now reports Fleet Telemetry or local history truthfully.
6. Dashboard cached GPS fallback replaced the source label for the entire live snapshot; only coordinate field provenance is now changed, producing an explicit mixed-source snapshot.
7. Charge cost silently invented `1.10/kWh`; missing cost now remains unavailable.

## Deliberate non-goals

- Do not synthesize missing historical GPS, routes, speed/power curves, charging curves or battery capacity.
- Do not reclassify `local_import_summary_only` as analysis-grade evidence.
- Do not add polling as a substitute for Fleet Telemetry streaming.
- Do not collect Tesla account passwords in MateLink.
- Do not change China Tesla Fleet/Auth hosts without separate live-provider qualification.
