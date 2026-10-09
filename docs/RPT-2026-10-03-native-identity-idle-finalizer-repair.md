# Native session identity and idle finalizer repair — 2026-10-03

Original baseline: `ad78840f0ebef0f69fbdbd4519e261bdff673223` on draft PR #12.
The reviewed slices were published on the existing branch on 2026-10-03:
qualification `8b7b199`, native identity `4aebbac`, and idle finalizer `39af788`.
The final implementation tree is `3ce7f09a38689e50c2f095424928b917823a5734`.
[Exact-head CI for `39af788`](https://github.com/Jovifei/tesla-master-mimo/actions/runs/37114324776)
passed. No merge, deployment, production DB operation, credential access,
raw-history deletion or retention change occurred.

## 1. Reproduced native identity collision

The native state machine generated a session ID from kind and starting timestamp
only. PostgreSQL uses a globally unique session primary key. Creation's previous
`ON CONFLICT (id) DO UPDATE SET id=EXCLUDED.id` could silently reuse a different
user/vehicle's row while reporting the event as accepted.

Isolated PostgreSQL reproduction: two users, distinct vehicles and VIN hashes,
identical charging start timestamp, and different energy/location observations.
All ten ingests returned accepted=1. User A retained one completed charge; user B
had none. Cross-owner queries remained empty. The demonstrated impact is **silent
history loss**, not a demonstrated cross-tenant disclosure or a confirmed
production incident.

Minimal repair: only newly created PostgreSQL native sessions receive an ID scoped
to user, vehicle, kind and start. Existing loaded session IDs, public IDs and
completion identity remain unchanged. Conflict acceptance additionally requires
matching owner, vehicle, kind and start; mismatches fail the transaction.

Green regressions retain each user's independent 5/8 energy and 31/41 latitude,
cover two vehicles within one account, and continue a pre-existing legacy open
session without replacing its ID/public ID. Independent real-PG probes additionally
cover shared-VIN drive fanout, legacy drive completion, replay/process restart and
deliberate owner/vehicle/kind/start conflicts that roll back event/latest writes.
Independent native-fix review found no blocking issue.

This repairs future writes after deployment; it does not recover already-missing
history or rewrite historical IDs. Any real-data recovery would need separate,
source-grounded investigation and authorization.

## 2. Reproduced idle drive timer failure

Both the published baseline and native-ID-only correction returned `count=0,
err=conn busy` when an idle parked drive became due for timer finalization. The
code attempted `tx.Exec` while the same pgx connection's SELECT rows remained open.
A later telemetry event could close the drive, but the timer itself failed.

Repair: select at most 100 due headers with row locks and `SKIP LOCKED`, consume and
close the reader, then perform updates in the same transaction. A tick handles one
batch; later ticks retry locked rows and drain any backlog. No raw arrays are
loaded or changed. The exact end remains stop-candidate time + configured debounce,
and existing session/public/completion identity is preserved.

Regressions prove: no early close, completion at the exact boundary with no later
event, idempotent repeated ticks, unchanged raw hash and IDs, canceled calls,
103-session bounded drain, locked-row skip/retry and unique completion keys.
Independent finalizer review found no blocking issue. Real-PG race probes repeated
three times additionally verify eight workers drain 303 due rows exactly once,
forced second-update failure and in-flight cancellation both roll back/retry, and
selected-row locks remain held after the reader closes. The limit bounds returned
headers/updates, not all PostgreSQL scan/sort work; a due-time partial index and
production-size query-plan validation remain future performance work.

## Verification and remaining work

Final local combined suite: **320 PASS / 0 FAIL / 0 SKIP** including subtests, with
isolated PostgreSQL 16.2 and Go 1.22.12. Vet, build, module verification, expanded
Linux race, formatting and diff checks pass. CI definitions now explicitly require
native-ID/finalizer PostgreSQL tests and include both in the race gate. Exact-head
CI for `39af788` independently passed **320 Go test/subtest records, zero failures
or skips**, all eight synthetic resource scenarios, the expanded race/vet/build/
module gates, **13/13 Web tests**, and the clean Web production build. The hosted
runner measurements are separate from the original local measurement receipts.

The previous eight synthetic resource scenarios remain separately identified by
their original source/receipt hashes in the qualification report. No new claim of
production capacity or of the original OOM being fully closed is made.

The next storage step remains a transaction-connected, tenant-scoped append/chunk
path with lossless raw preservation, shadow comparison, backward-compatible reads
and explicit old-writer/version guards before switching realtime ingestion away
from whole-session JSON read/modify/write. The existing 10-second downsampled route
cache cannot substitute for original points. Broker/network/database restart,
realistic long-duration system resources and device/production acceptance remain
open.
