# Vehicle Data and Analytics Design

## Goal

Make the Android app trustworthy with real TeslaMate data: compact vehicle status, a drive-and-parked timeline, correct sync-backed analytics, editable charge costs, Chinese locations, and source labels.

## Decisions

- Android and a new Go 1.22 MateLink Adapter are the delivery scope. iOS and Web will later consume the same adapter contract.
- The adapter owns port 8080, proxies legacy `/api/v1/*` requests to TeslaMateApi, and exposes `/api/matelink/v1/*` for snapshots, timeline, charge overrides, geocoding, sentry events, and capabilities.
- The adapter uses a separate PostgreSQL `matelink` schema. It never modifies TeslaMate-owned tables.
- Drives shorter than 0.5 km are short relocations inside the surrounding parked interval. They are not rendered as route cards.
- Charge cost precedence is manual override, positive TeslaMate cost, then 1.10 in the selected currency per kWh. A zero API value is unknown, not free.
- Address precedence is adapter AMap result, encrypted app AMap Web Service key result, local cache, then original TeslaMate address.
- All user-facing timeline times use `HH:mm`. Missing data is unknown, never zero or false.
- Battery health is historical estimation. When capacity samples are unavailable, show range retention rather than invented capacity or SOH.
- Sentry events are inferred from status state and explicitly labeled. Media remains unavailable until a USB/NAS source is added.

## Dashboard Layout

The dashboard has a vehicle header, one battery/range/weighted-efficiency primary band, compact state chips, a 2x2 TPMS panel, and recent activity. It uses the documented Precision Minimalist token set: restrained borders, 8dp corners, no elevated-card stack, Chinese copy, and tabular numbers.

## Data Quality

The app distinguishes live MQTT snapshot, persisted snapshot, historical fallback, calculated estimate, cached address, and unavailable data. Analytics use total energy divided by total distance; no average is computed by averaging per-drive averages.
