# Compact bootstrap contract before persistence integration — 2026-10-03

Baseline: `360e57834b19c7d0b454558ec06b5881b91e036e`, draft PR #12.
This slice supplies the transaction-local entry gate for the next compact
PostgreSQL adapter. It is **unwired**: no production caller, state table,
activation command, startup scan, writer, reader or migration changes.

## Entry contract

`prepareCompactBootstrap` accepts an existing transaction and explicit account,
vehicle, binding hash, source, reducer/field-slot version and debounce expectation.
It checks that the vehicle belongs to the account, not merely that separate
foreign keys to a user and vehicle exist. The binding must match exactly.
Only version 1 with its 31 fixed slots and `telemetry_mqtt` source is supported;
a future core version/slot-count change cannot implicitly widen this contract.

The actual database already enforces at most one active drive and one charge
through `jourvolt_telemetry_open_session_idx`. The helper verifies its table,
ordered columns, exact predicate, uniqueness, immediate enforcement, validity,
readiness and live status. It does not trust the name alone. A defensive active
projection returns at most three short kind values, including an overflow sentinel.

**Any existing active session requires drain.** The helper neither reads its raw
arrays nor converts it into compact state. Valid old sessions continue on the
legacy path until they naturally finish; no forced closure or guessed prefix is
introduced. A scope may remain legacy indefinitely if its session never closes.

For an idle scope, at most 32 latest metadata rows are returned (31 plus sentinel).
Unknown/noncanonical fields are checked rather than filtered away. Field names
are limited to 64 bytes, declared value hashes to 64 hexadecimal characters,
and the two required numeric observations to 1 KiB each before transmission.
Wrong source/type, null numeric values, nonfinite/overflow values, duplicates and
excess rows reject the candidate. Errors return no partially usable state.

All 31 predecessor timestamps are preserved exactly as PostgreSQL returns them.
Only VehicleSpeed and GpsHeading values seed defaults; other fields contribute
watermarks. Historical Location/TPMS JSON is not transferred just to discard it.
Gear restoration stays consistent with the existing PostgreSQL loader, which
does not hydrate it. This is not a validation of every historical JSON value.

The candidate contains empty session state and a fingerprint of bounded reducer
inputs plus declared hashes. The fingerprint is **not** a database revision,
whole-payload checksum, verified generation or writer-fencing token. No existing
session ID/public ID/completion identity is changed. An adapter must establish
durable versions/revisions and fencing in the same transaction; this result is
not safe to retain and activate after the transaction finishes.

## Locking and cancellation

The helper requires READ COMMITTED so it obtains fresh predecessor data after
waiting for earlier ingestion. REPEATABLE READ/SERIALIZABLE callers are rejected
instead of silently inheriting an older transaction snapshot.

It acquires `SHARE UPDATE EXCLUSIVE` on the session table before checking the
index, then `FOR UPDATE` on the exact mapping and `FOR SHARE` on its owning
vehicle. Only after that does it inspect active sessions and latest rows. Legacy
ingest holds `KEY SHARE` on the mapping through commit, so prior mapped ingestion
must finish first. Candidate preparation retains these locks for the caller.

The table lock permits ordinary `ROW EXCLUSIVE` ingestion, while serializing
other bootstrap calls, concurrent index maintenance and some DDL. It can delay
VACUUM/ANALYZE/autovacuum on that table. It is for rare explicit activation, not
for every event. The helper has the existing five-second database context, with
earlier caller cancellation respected. **The caller must also bound the rest of
its transaction**: returning from the helper does not release its locks. These
are normal PostgreSQL lock semantics, not a zero-downtime migration claim.
[PostgreSQL 16 locking reference](https://www.postgresql.org/docs/16/explicit-locking.html).

Real PostgreSQL tests observe lock waits before canceling, prove latest rows are
not locked before the mapping, prove a newly committed predecessor is visible
after waiting, and block real legacy ingestion until the caller commits. A
different ownership scope still ingests while the table lock is held. Both
regular and concurrent index-drop attempts are blocked in a dedicated synthetic
schema, canceled before release, and leave the checked index valid.

This gate does not exclude a privileged direct SQL writer that ignores the
mapping protocol. It is not the old-writer fence required for live activation.

## Next adapter and compatibility sequence

1. **Atomic compact bridge.** Use this gate in the transaction that explicitly
   switches an idle scope. Add a durable owner/binding/source/version/debounce
   contract, writer epoch, state revision and header revision covering every
   relevant scalar/detail change. Summary revision alone does not cover timer
   completion or other header-only writes. Enforce int32 persisted count limits.
2. **Preserve actual admission.** Resolve and lock bounded mappings in stable
   order, then state/latest/session/shadow rows. Retain multi-scope atomicity.
   Perform the actual stale precheck, scoped ID insert and guarded latest UPSERT;
   transition only on admitted updates. Keep post-insert consumed-ID semantics.
   Reusing the already-overwritten latest row as predecessor would be incorrect.
   Match raw event ID/completion timestamps and persisted PostgreSQL timestamp
   round trips against the real legacy store; do not normalize away differences.
3. **Commit all derived effects together.** Scalar state, new sample, complete
   legacy JSON append, bounded shadow delta, summary and completion commit before
   ACK. Add a count/delta shadow append API rather than recreating full arrays.
   Timer finalization must use the same state/fence protocol. Version/revision
   conflicts must be persistence errors, never fabricated durable duplicates or
   permanently invalid input that MQTT would acknowledge.
4. **Exclude old writers.** Database guards for enabled scopes must reject late
   legacy mutations, including timer and source/identity changes, using the
   expected writer epoch. A feature flag alone does not fence an old binary.
   Drain/upgrade old workers and test old startup migrations. An epoch marker is
   an application protocol fence, not a security boundary against the DB owner.
5. **Use the real entry points.** Test production ingest and timer dispatch with
   synthetic explicitly enabled scopes. Legacy active scopes keep their existing
   path. After activation, conflicts cannot silently fall back to old writes.

The bridge can eliminate whole-array read/decode/clone/re-encode in Go by appending
only the delta to legacy JSON in SQL. It still decompresses/rewrites growing
PostgreSQL JSONB and runs summary triggers. It is an intermediate compatibility
step; moving work into SQL does not bound database cost or close the OOM issue.

Before switching to chunk-only authority, implement complete legacy detail
responses with a bounded per-chunk encoder. Preserve IDs, envelope, ordering,
null/zero, native/archive conversion, timestamp formatting and charge aggregate/
fallback fields. Verify full source identity/revisions, counts/order and hashes;
missing-prefix or stale shadows cannot masquerade as complete details.

Stage transformed detail bytes into a private temporary file under request/global
disk and worker budgets, maintaining bounded header accumulators. Verify the
complete source before sending a successful envelope, then stream it. Legacy SQL
slicing may still incur large TOAST/scan work. Oversize/corrupt sources, quota
exhaustion or cancellation must not produce a truncated successful array. A new
paginated endpoint alone is not compatibility for existing full-detail clients.

## Verification and remaining risk

New-API contract tests were written first and failed to compile before the helper
existed. This is test-first development, not a claimed production defect. Isolated
PostgreSQL tests cover ownership, zero/one/two active sessions, a deliberately
invalid three-kind fixture, wrong/missing index definitions, latest overflow and
duplicates, invalid/oversize metadata/scalars, and an unused 8 MiB Location value.
Read-only attempts verify source history/latest/admission fingerprints unchanged.

The full isolated Go/PostgreSQL suite passed **461 records, zero failures/skips**;
vet, module verification, build and expanded race passed. Web
history regressions passed **13/13**, with clean installation and production
build. Focused bootstrap PostgreSQL/race ran three times; final exact-source
receipts and independent review are recorded with the publication handoff. CI
requires all six bootstrap test groups and includes them in race coverage.

Future additive schema work still takes DDL locks. Bridge rollback requires
drained writers/timers and verified complete legacy data before resetting the
scope mode/epoch. After chunk-only authority, an old binary needs a separately
verified legacy reconstruction; turning off a flag is insufficient. Preserve
original sources/generations. Actual integrated RSS, database CPU/block activity,
full HTTP responses and process/broker/database replay remain pending. This
unwired helper is not an ingestion-memory improvement or production qualification.
