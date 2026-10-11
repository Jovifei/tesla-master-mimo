# Bounded native shadow comparison — 2026-10-03

Baseline: `efda19807e380afe689bd47d60b47a48405e9064`, draft PR #12.
This adds explicit, resumable comparison before the future rebuild/read-cutover
step. Original session JSON and existing chunks are never changed by comparison.
Partial/stale shadows still require a separate generation-preserving rebuild.

## Bounds and cursor contract

One maintenance invocation compares at most **16 chunks**, with at most **256
samples and 65,536 encoded bytes per chunk**: at most 4,096 samples and 1 MiB of
distinct chunk payload read into Go over the invocation. Equality checks bind
those bytes back to PostgreSQL, so bidirectional payload traffic can reach 2 MiB
plus protocol/metadata overhead. Go holds one chunk at a time.
All database work uses a five-second context budget. PostgreSQL may still detoast
or slice the large legacy JSON value; these limits do not establish a PostgreSQL
memory, CPU or physical-I/O bound.

The caller supplies an explicit user, vehicle and public session ID. A new job
starts at chunk/sample zero; only its opaque job ID is accepted on resume.
Chunk/sample offsets and cumulative digest come from the locked database cursor,
not caller input. Each successful batch commits its checkpoint atomically.
Failure or cancellation before commit preserves the prior checkpoint; an error
does not prove an uncertain commit was undone. A retry re-reads durable progress.

Only completed native sessions with supported summary/encoding versions, coverage
from session creation, zero missing prefix, matching source revision/count and
non-stale shadow metadata are eligible. Source session identity, public ID,
start/end/completion identity, expected totals and source/content revisions are
pinned in the job and rechecked on every resume, including already-finished jobs.

Every chunk must have the next exact chunk/sample index, positive bounded count,
bounded bytes, a matching SHA-256 and decoded array length, and JSONB equality to
the corresponding legacy sample slice. Completion additionally requires both
expected totals and absence of trailing chunks. A framed/versioned hash chain
records the ordered comparison, including scope, indices, sizes and payload hash.
It is a comparison digest, not a full raw-event archive checksum or device ACK.

## Locking and mutation detection

All batches lock **session SHARE → manifest SHARE → job UPDATE**. This agrees
with parent/cascade lock order and serializes concurrent resumes without holding
a child cursor while waiting for its parent. Chunk reads are ordinary MVCC reads:
locking a chunk after its manifest could deadlock with a chunk writer whose
revision trigger is waiting on that manifest.

Every ordinary chunk INSERT/UPDATE/DELETE transaction increments the manifest's
content revision. Ownership moves and TRUNCATE are rejected. Failed mutations
roll back their revision increment. Audit jobs reference their manifest with a
composite cascading FK, so deleting/recreating a manifest/session cannot preserve
an old cursor with a reused zero-based revision. Account deletion retains its
existing cascade semantics; comparison never initiates deletion.

Finished jobs mean **verified at the recorded revisions**. They are not permanent
certificates: future use must revalidate the current source/content revisions and
eligibility. Privileged counter resets, disabled triggers, schema replacement and
arbitrary direct audit-table edits are outside this application guarantee.

## Explicit maintenance only

Set `JOURVOLT_AUDIT_NATIVE_SHADOW=1` with an explicit `DATABASE_URL` and all three
scope variables:

- `JOURVOLT_SHADOW_AUDIT_USER_ID`
- `JOURVOLT_SHADOW_AUDIT_VEHICLE_ID`
- `JOURVOLT_SHADOW_AUDIT_PUBLIC_ID`

Run the API binary once. It returns a small JSON result with `job_id`, progress,
batch bytes/chunks and revision-stamped completion. To continue, run again with
the same scope and `JOURVOLT_SHADOW_AUDIT_JOB_ID` from that result. Never infer a
valid resume from a guessed offset. A wrong-scope job is rejected.

This mode assumes an already-migrated, explicitly selected database and exits
before startup migrations, provider configuration or network listeners. It rejects
the summary-backfill flag being enabled simultaneously. Errors are logged without
raw payloads or database connection details. There is no automatic history scan,
repair loop, new HTTP API, read cutover, deletion or retention change.

## Isolated verification and next step

Focused PostgreSQL tests passed three repetitions under the race detector. They
cover drive/charge comparison across a fresh OS process, 19 chunks over multiple
invocations, empty completed history, concurrent resumes, old-source and chunk
drift, completion-identity changes, missing/extra/mismatched chunks, wrong scope,
ineligible shadows, cursor-write fault, observed in-flight cancellation and retry.

Lock tests observe actual PostgreSQL waits. A parent-lock test proves the cursor
remains free, exercises cascade deletion inside a transaction and rolls it back
without committing any history deletion. A blocked chunk writer test proves
comparison can read its preceding MVCC version and later rejects the committed
revision change. Guard tests cover failed DML rollback, ownership moves and
TRUNCATE rejection. Existing raw-data fingerprints remain unchanged by audits.

Final local aggregate validation: **353 Go/PostgreSQL test/subtest records pass,
zero failures or skips**, plus vet, build, module verification, expanded race,
formatting and diff checks. Independent frozen-source review found no blocker;
that reviewer did not claim an additional database run. Exact-head remote CI is
reported on PR #12. The workflow explicitly requires all eight new PostgreSQL
comparison tests and includes them in the existing race gate.

The next step is an explicit, bounded rebuild into a new generation for legacy
prefix/stale shadows, preserving original payloads and previous chunks, followed
by independent comparison. Fixed-size native state, generalized archive/chunk
reads, complete raw-event capture, broker/database restart and realistic resources,
device restore/ACK and production acceptance remain open. The original OOM is
still not considered closed. No production DB, deployment, main merge, credential,
raw cleanup or retention operation was performed.
