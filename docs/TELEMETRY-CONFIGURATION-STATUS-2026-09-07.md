# MateLink Telemetry Configuration Status

Date: 2026-09-07

## Confirmed completed

- Tesla account authorization is active: the app can read the vehicle name, state, battery, range, temperature, and other on-demand Fleet `vehicle_data` fields.
- The AMap key is configured. It can render a map and reverse-geocode a coordinate once one exists.
- The API, MQTT broker, Fleet Telemetry container, and vehicle-command proxy are running.

## Confirmed not complete

Production checks after the 2.1.6 deployment show:

| Evidence | Value |
| --- | --- |
| Telemetry pairing rows | 0 |
| Latest Telemetry rows | 0 |
| Telemetry event-buffer rows | 0 |
| Route-point rows | 0 |
| Readiness | `awaiting_first_event` |

Therefore the vehicle has not successfully started Fleet Telemetry delivery. AMap cannot display a vehicle position without a source coordinate. Existing local imports have no route points, addresses, or speed/power samples and cannot truthfully reconstruct them.

## Current application behavior

Telemetry configuration is currently an explicit Data Status action. The backend does not silently configure a vehicle after login. This is why an OAuth-authorized account can still have `pairing=0`.

The configuration action must complete successfully before the vehicle can begin sending Telemetry. The server then needs one genuine vehicle event before location, route, completed charging history, and curves become available.

## Decision required for automatic configuration

Automatic configuration is a product-policy change, not a map-key change. Before implementing it, review and decide:

1. Whether a successful Tesla OAuth callback is explicit consent for MateLink to submit the Telemetry configuration request for every selected vehicle.
2. How the app must present Tesla's virtual-key or vehicle permission confirmation when Tesla requires it. The app must never conceal or bypass a Tesla confirmation.
3. Whether configuration should run immediately after login, after vehicle selection, or only after an in-app consent toggle.
4. How failures are displayed and retried without repeatedly waking the vehicle or issuing duplicate configuration requests.

## Acceptance evidence after configuration

Do not call Telemetry complete until all are true:

- a persisted pairing exists and reports `config_synced=true`;
- at least one `latest` record and route point are stored;
- a real drive creates a route-bearing drive session;
- a real charge creates a charge session with measured fields;
- the API response, PostgreSQL rows, and phone pages agree on source and timestamp.
