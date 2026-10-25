# PR #27 — 2026-10-25 remote final-review repair: parked adjacency

This source-stage note follows `docs/DATA-FOUNDATION.md` and the PR #27 handoff.
The review reference was `af6fe089a57c95b3839dc304bb0385c3ba4e8e29` (tree `d400bc9...`).
Production, the owner's E-drive worktree, device state and main branch are out of scope.

## Confirmed mismatch and patch

- Android list previously zipped **only drives >=0.5 km**. A physically intervening
  0.1 km drive (even when intentionally hidden) could become invisible to the
  parked-window inference, unlike Go `interveningParkedDrive`, which rejects
  all intervening drives including short, open and quarantined ones.
- `DrivesViewModel` now computes parked eligibility from full pre-UI-filter
  history and passes only proven adjacent pairs to the list renderer. A hidden
  intermediate item, distance-filter removal, unknown timestamps or conflicting
  source-instance evidence cannot create a new parked item.
- Direct Room offline parked reads now run a car-scoped SQLite EXISTS check
  against **all** stored intervening drive rows, even quarantined ones. Both
  local parked-detail and local charge-session publication are vetoed if the
  boundary is not adjacent. An unrelated local vehicle key cannot veto an
  otherwise valid pair. No history row is deleted or reclassified by the patch.
- True two-drive same-instance windows, exact zero kWh charge receipts and the
  server's distinct charge-stop-recharge sessions remain intact.

## Regressions added

- JVM `DrivesHistoryVisibilityTest.hiddenShortDriveAndDistanceFilterCannotBridgeANonAdjacentParkingGap`:
  positive pair, hidden short middle, quarantined/foreign middle, source proof.
- JVM Robolectric `ParkedRoomAdjacencyIntegrationTest`: real persisted Room
  two-drive and local charge consumers before/after reopen, short quarantined
  middle blocks both; foreign car retains valid window and saved history.
- Android instrumentation `ParkedDetailScopedRoomAndroidTest.interveningShortOrQuarantinedDriveStopsLocalParkedAndChargePublication`:
  real Room positive/negative scope and local charge publication.

Exact-SHA Actions results must be checked independently before SOURCE_APPROVED.
The separate Fleet natural-data, permissions, two-account, TPMS, notification,
physical device and production decisions remain open; synthetic CI is not Fleet acceptance.
