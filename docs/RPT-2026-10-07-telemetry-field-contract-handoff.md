# Telemetry field contract handoff

Base: API 36d630a8 / phone 2d1828be. This branch intentionally excludes parked full release PR12.

Scope for the next bounded implementation slices:

- Preserve identity, tenant, vehicle, source and unknown/null semantics.
- Never convert SOC percentage into kWh. kWh/100km requires measured energy or a clearly labeled estimate with assumptions.
- Charging start/end SOC must keep real zero and unknown distinct.
- Speed, energy, tyre pressure and notifications require source evidence and regression coverage.
- Notifications must not replay first sync history, must deduplicate by identity, and must handle permission denial/background limits.

Out of scope:

- historical SOC backfill
- vehicle wake/request
- synthetic trips
- TTL changes
- parked full release
- bridge persistence migration
