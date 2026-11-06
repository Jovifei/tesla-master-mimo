# Compact native transitions, before persistence integration — 2026-10-03

Baseline: `cfd5c7c206b6ed6c300ecca540e06826cf1e0532`, draft PR #12.
The storage ADR identifies whole-session read/clone/rewrite on every event as
the next ingest-memory target. This slice adds an **unwired**, package-private
scalar/count transition core and compares it with the unchanged legacy reducer.
No PostgreSQL writer, reader, schema, startup path or request behavior calls it.

## Versioned bounded state

Version 1 contains explicit account/vehicle scope, two scalar session headers,
exact int64 route/charge counts, stop candidate, charge-energy baseline and field,
recent speed/heading/gear observations, and 31 fixed field timestamp slots. It
retains no sample arrays, completed history, event-ID set, maps or interfaces.
Header strings are bounded and copied; nullable scalar pointers are owned copies.

Each admitted event returns fresh state, at most one sample delta and at most
two completion headers. A charge terminal event can also complete a due drive;
their legacy ordering is preserved. Count overflow rejects the transition without
changing input state. Quality uses the total route count, not just this event's
delta. Null, explicit zero and absent evidence remain distinct.

Fresh session IDs use the existing PostgreSQL ownership-scoped formula. Restored
legacy IDs/public IDs are preserved; allocation of a fresh database public ID is
still the future persistence adapter's responsibility. Completion identity uses
the existing ID/kind/end-time formula. Event-driven completion uses the event time;
timer completion uses stop-candidate plus debounce, matching the existing reducer.

The 31-slot order is part of the versioned contract. Unknown/noncanonical field
names, unsupported versions and invalid ownership/state return errors. Tests
require every documented field to have a slot; adding a field requires an explicit
version/bootstrap compatibility decision. Timestamps remain `time.Time`, without
guessing units or globally reordering observations from different fields.

## Admission is deliberately separate

The core accepts canonical events **after the caller's durable scoped admission**.
It does not implement lifetime event-ID deduplication and has no bounded cache
masquerading as that guarantee. Its fixed timestamp slots defensively reject
non-advancing observations with `Applied=false`; they cannot replace event-ID
admission across fields. The existing event-buffer retention is 24 hours, while
persisted per-field watermarks also reject unchanged historical replays.

The differential oracle keeps the original reducer unchanged. A test adapter
implements its serial PostgreSQL-shaped admission and post-transition fresh-ID
assignment; it does not share compact transition logic or change values to hide
differences. The same admitted event stream is then compared after each step for
headers, totals, new samples, baselines, observations, quality and completions.
The oracle's unbounded Seen/history structures belong only to test code.

Real isolated PostgreSQL tests separately establish admission behavior, including
reconstructed-service replay, cross-field identity reuse and tenant isolation:

- A serial stale/equal timestamp rejects before inserting the event ID. A later
  fresh timestamp can reuse that previously rejected ID.
- The guarded latest-value UPSERT can reject after the ID insertion, for example
  when distinct Go nanoseconds become the same PostgreSQL microsecond. That ID
  remains consumed, and a later retry with it still rejects.
- Fresh unique microsecond observations admit. Unchanged replay and another field
  carrying an already-consumed ID reject; another ownership scope is independent.

These are actual store receipts, not conclusions inferred only from the serial
oracle. The oracle does not claim to simulate transaction races or database time
precision. Its equivalence claim is restricted to the common admitted stream.
Synthetic ID reuse with changed timestamps probes the storage boundary; it is
not presented as a normal canonical vehicle-event encoding.

## Required gates before any live integration

1. Persist an explicit scalar-state/field-slot version and validate complete
   account/vehicle/session/source identity and expected source revision under the
   same locks as ingestion. Counters must fit their target database types. A
   mismatch rejects/retries without silently rebasing or discarding old details.
2. Bootstrap only from trusted bounded metadata whose typed native format and
   counts were established. A JSONB equality/rebuild stamp does not prove that
   arbitrary legacy samples have the native typed shape. Existing active sessions
   need a separate compatibility plan; do not automatically load/repair all raw
   history at startup or pretend missing prefixes are complete.
3. Preserve actual transaction order: scoped event admission, guarded latest
   update, scalar transition, appended samples/summary and completion must commit
   or roll back together before acknowledgement. Construct transition watermarks
   from the predecessor state, not a latest row already overwritten by the event.
   Use actual database admission results and persisted timestamp precision.
4. Preserve existing IDs/public IDs and completion uniqueness. Coordinate old
   writers with source/format revision gates. A late legacy writer must not
   overwrite compact state or cause a stale snapshot to replace complete history.
5. Keep complete legacy detail compatibility until independently verified versioned
   readers exist. Completed immutable generations are not a live append target.
   SQL concatenation could bound Go transfers while still rewriting large JSONB
   values in PostgreSQL; neither that cost nor the full-detail read cost is solved
   by this standalone core.
6. Qualify the integrated pipeline with real PostgreSQL replay/rollback/concurrency,
   process/broker/database restart and long-history resource measurements before
   attributing an operational memory improvement or closing the OOM issue.

## Validation scope

Reference-contract tests were written and passed first on the unchanged reducer.
Differential/API tests then failed to compile because the compact API did not yet
exist; implementation made them pass. This is new-API test-first evidence, not a
claim of a previously observed production bug.

Differential coverage includes deterministic drive/charge edge cases and eight
seeded 600-event traces with replay, cross-field ID reuse and out-of-order times.
It compares every emitted sample and completion, including charge baseline
switching, zero deltas, stop/resume/debounce and simultaneous completion. Other
checks cover wrong ownership, contract version/field changes, legacy IDs/public
IDs, route-quality totals, count overflow, nanosecond and saturated-duration time
cases, immutable input/output ownership, and 20,001 route samples without retained
history. Equal allocation counts at small and large seeded totals concern only
this pure core; they are not process RSS or pipeline capacity measurements.

Focused compact plus actual PostgreSQL admission tests passed race ×3 (51
test/subtest records, zero failures/skips). The full isolated Go/PostgreSQL suite
passed **426 records, zero failures/skips**, with vet, module verification, build
and expanded race (15.850 seconds). Web history tests passed 13/13, with a clean
install and production build. Exact-head hosted results are reported in PR #12
after publication. Existing reducer and
production ingestion source remain unchanged. No production DB, migration,
deployment, merge, credentials, cleanup or retention change was performed.
