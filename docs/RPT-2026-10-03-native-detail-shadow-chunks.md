# Native session detail shadow chunks — 2026-10-03

Baseline: `8bb7564437879ed8ce011660ac9ebeaa9470bac7`, existing draft PR #12.
This implements the next additive comparison step in
`ADR-2026-10-02-tiered-history-storage.md`. It shadows the native session's
**derived route or charging samples**, not every original telemetry observation.
Legacy JSON and the existing full-detail responses remain authoritative.

## Connected writes and explicit coverage

Native PostgreSQL ingestion appends only the sample delta produced by an accepted
event. Its event deduplication, latest values, session JSON, persisted summary,
shadow manifest and chunks commit or roll back together. A chunk failure returns
an ingest error; it does not acknowledge a partial transaction.

Each JSON-array chunk has at most **256 samples and 65,536 encoded bytes**, including
array punctuation. A single oversized/invalid sample fails instead of truncating.
The encoder emits one bounded array at a time. Rows store their ordered chunk and
sample-start indices, sample count, payload bytes and SHA-256. Database constraints
check byte/sample bounds, decoded array count and the actual payload SHA-256.
The manifest explicitly records `native-session-json-v1`, version 1 and no compression.

Composite foreign keys bind both tables to the same session/user/vehicle. All
native shadow queries also include that scope. Same-time events in two accounts
produce separate histories and chunks. The ingest path only inserts chunks; this
is an **append-only writer guarantee**, not protection against arbitrary direct
database edits/deletes or an administrator replacing schema constraints.

A manifest is eligible for whole-session comparison only when its creation was
proved by this transaction's successful INSERT. An existing ID's conflict path
locks and checks ownership and never labels it fresh. Pre-existing open sessions
start at their prior sample count, retain an explicit missing prefix, and are
marked as not started with the session even if that prefix has zero samples.
There is no implicit historical backfill.

Before each append, the manifest must match the locked session's pre-write summary
revision and sample count. The current state machine preserves existing samples and only appends. A future
caller that edits a prefix inside the same transaction needs an explicit prefix
comparison or a new revision contract; length checks alone cannot prove that
stronger condition. The manifest records the actual post-write revision, including
accepted events that produce no sample. Old-writer JSON
rewrites, even at the same count, make the revision mismatch detectable. Subsequent
new ingestion marks that shadow stale and stops extending it while preserving
normal legacy ingestion. Existing chunks are retained for investigation, without
silently rebasing them. Summary backfills can conservatively cause the same stale
state; the summary revision is not claimed to be an immutable detail version.

Idle finalization changes no point payload or detail revision. Completion remains
derived from the session's end state. Before a future read cutover or archive ACK,
validation must also establish supported encoding, zero missing prefix, matching
revision/total count, contiguous chunk ranges, verified payload hashes and full
decode. A manifest row or successful ingest alone is not an archive durability ACK.

The existing ingest reader previously ignored JSON decode errors. It now returns
an error before rewriting malformed legacy detail as an empty array. Session
serialization errors also roll back the event transaction.

## Verification

All checks use synthetic data in isolated PostgreSQL 16.2 with Go 1.22.12 on Linux.
The final local full suite has **334 passing test/subtest records, zero failures
and zero skips**. Vet, build, module verification, the expanded race gate,
formatting and diff checks pass. The focused new tests plus killed-process replay
also passed three repetitions under the race detector. Independent source/docs
review found no blocking issue. Additional isolated probes verified partial-array
decode rollback, two tenant mappings (including downsampled route rows) rolling
back together on a chunk failure and succeeding on retry, and a same-count rewrite
of a covered sample becoming stale without changing the prior chunks.

New coverage checks:

- Exact point and encoded-byte boundaries, one-over rejection, byte splitting,
  invalid numeric samples and emitter errors, without losing samples
- Drive/charge reconstruction against the full legacy sample arrays, including
  timestamps, null/zero values, coordinates and energy deltas
- Empty-delta writes, exact replay and 12 concurrent duplicate deliveries
- Legacy missing-prefix coverage and same-count old-writer invalidation
- Chunk failure rolls back event buffer, latest state, legacy payload, summary,
  manifest and chunks; the same event succeeds exactly once on retry
- Malformed legacy JSON remains unchanged after rejected ingestion
- Tenant scope, ownership foreign keys, checksum/count constraints and unknown
  schema rejection; an already-existing ID is never certified as newly created
- The existing actual killed-child/fresh-child process test now additionally
  verifies retained chunk content, continuous coverage and exact final samples

CI explicitly requires the six new PostgreSQL tests and includes the chunk encoder
and native-shadow cases in the race gate. Remote results must be read for the
actual PR head; the local count above is not itself a remote CI result.

## Remaining limits and next gate

This is migration groundwork. Native ingestion still loads/clones/marshals the
entire open session JSON, and old full-detail reads still materialize full arrays.
The original OOM is **not closed**. The shadow adds database storage/write work;
current single-event appends normally create one chunk row per sample. Compression,
chunk packing, and realistic storage/CPU/I/O cost need separate measurement.

The additive schema builds a composite unique index and takes ordinary DDL locks;
this is not zero-downtime migration evidence. Stale/suffix-only shadows need an
explicit bounded rebuild/comparison process before becoming a read source. No
new API exposes shadow chunks, and imports/archives are not shadowed by this slice.

Next: compare/rebuild covered histories, then replace native whole-session state
with bounded scalar/last-sample state and append deltas, preserving quality counts,
charge baseline, debounce and completion identity. Read-version negotiation,
old-writer guards, broker/network/database restart tests, full raw-event retention,
private cold archive, device ACK/restore and production capacity remain open.
No deployment, main merge, production database operation, data deletion, retention
change or new credential was performed.
