# Full-stage local return: natural power window vs API boundary precision

Status CHANGES_REQUIRED for the same remaining stage. Immutable client8625d28, deployed-source bb09fac; current strict source guards remain valuable. Source evidence is separate from the intermittent TLS peer issue.

Observed2026-10-09T01:11:53Z / China09:11:53, scoped read-only cloud query of the user-authorized natural Oct7 record:
-3100points; full-precision power window complete; start/end both have fractional seconds; no null-power points, duplicate conflicts or gap>30seconds.
-The bb09 detail serializer formats start/end with time.RFC3339 (drops fractions), but archive point.Date is preserved with source fractional precision.
-Independent exact-window calculation under that serialized shape: missing0.417seconds, coverage0.9995990384; complete=false. No real date/location/account/VIN/token/power trace emitted and no data write.

Actually executed independent JVM replay against the compiled8625 product DriveEnergyCalculator class (not a rewritten implementation), exit0:
-Five synthetic1kW samples at30-second spacing over a120-second window with matching0.417 fractional start/end: complete=true.
-Same samples with the serializer's whole-second start/end shape: complete=false, reason=incomplete_power_coverage, missing0.417seconds.
-This is a synthetic precision replay supported by a real natural-source condition, not a fresh authenticated response capture or natural phone energy PASS.

Phone snapshot01:06UTC: normal TLShealth200/verify0, no recorded HistorySync failure/cache-warning, energy unavailable. Three UI dumps missed lowerSOC while swipes touched charts; a margin scroll found percentage text. Do not assert a SOC parser bug solely from earlier incomplete UI sampling. Field names date/power/battery_level/battery_details bind consistently and bb09 archive detail is not downsampled.

Complete the SAME full stage by repairing proven boundary precision across serializer/contracts and meaningful Go→JSON→Moshi→calculator/consumer regressions. Preserve exact observed time/measurement semantics, zero/null/negative sign and existing whole-window safeguards; do not relax complete coverage, invent/interpolate missing source intervals or claim old API becomes fixed without a separately qualified deployment. If old production's second-resolution output cannot safely yield full-window evidence, state its compatibility/deployment gate explicitly and prepare the smallest audited reversible API repair plan. No production schema/data/bridge changes or history backfill authorized by this source finding. Include this issue with the TLS rollback/validation/injection package14a6fce and final immutable CI/handoff, rather than returning a one-line date patch.
