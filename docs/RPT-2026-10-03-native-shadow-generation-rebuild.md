# Bounded native detail generation rebuild — 2026-10-03

Baseline: `3c3ff71753e898b2e6808b4415fdac47a61063a9`, draft PR #12.
This implements the next explicit rebuild step for completed native histories
whose ingest shadow is missing, has an old prefix, or is stale. It creates a new
generation from the authoritative session JSON. Original session JSON, ingest
shadow rows, and previous generations are not rewritten or removed.

## Lifecycle and selection

Each new start receives a random, durable generation ID and a database-generated
ordinal. Starting again without that ID creates a preserved sibling, not an
inferred resume. Only an existing generation ID can resume its database cursor.
The generation pins account, vehicle, session/public identity, stream, start/end,
completion identity, source revision, expected sample count and encoding.
Only completed `telemetry_mqtt` sessions with valid materialized summaries qualify.

One invocation performs exactly one phase:

1. `building` appends bounded chunks and atomically checkpoints progress. The
   final build batch freezes the generation and initializes verification at zero.
2. A later `verifying` invocation independently checks the frozen chunks against
   the source. It verifies order, sample counts, byte limits, SHA-256, decoded array
   length and JSONB equality, accumulating a framed digest. Its genesis binds the
   generation, ownership, source identity, totals and encoding even for no samples.
3. The last successful verification transaction marks the generation `verified`
   and conditionally advances a scoped selection pointer. Only a higher ordinal
   can replace the current pointer. An older verified sibling remains preserved
   but unselected. Repeating a finished generation checks freshness and reports
   selection; it never republishes and displaces a newer selection.

The pointer is selection **at recorded revisions**, not a permanent certificate.
Every resume, including a finished one, rechecks source identity/revision and
generation revision. Future readers must revalidate all pinned identity fields
and eligibility as well as both revisions; timestamp/completion-identity changes
need not increment the summary revision. This slice
does not switch existing detail readers or the live ingest writer to generations.

## Bounds and data representation

Each invocation processes at most 16 chunks, each containing at most 256 samples
and 65,536 encoded UTF-8 bytes. At most 4,096 samples and 1 MiB of distinct payload
enter Go per invocation. Building writes those bytes back; verification sends
them back for equality checks. Bidirectional payload can therefore reach 2 MiB
plus metadata/protocol overhead. Go handles one chunk at a time.

All database work shares a five-second context. The source slice is computed in
PostgreSQL, and oversized candidates return only size metadata and NULL payload.
Candidate sample counts halve from at most 256 to one, at most nine attempts per
output chunk. An oversized single sample fails without advancing the checkpoint.
These caps do not bound PostgreSQL detoasting, JSONB slicing, CPU, memory or I/O.

`legacy-jsonb-array-v1` preserves JSONB sample values, unknown fields, nulls, zeros
and order. It does not promise the original JSON byte formatting, nor every raw
telemetry event. Empty/null sample arrays produce zero chunks with a scoped
verification genesis. The source summary and revision remain authoritative.

## Locking, immutability and recovery

Lock order is source session SHARE → generation UPDATE → selection pointer.
Generated chunks use a BEFORE INSERT guard that locks/increments only their
generation before unique-key arbitration. The guard rejects insertion after the
build phase. UPDATE, direct DELETE and TRUNCATE of generated chunks are rejected.
Generation identity, ordinal and source pins cannot change; direct generation
deletion is rejected while its source exists.

Child guards never acquire source or pointer locks. Deletion checks use plain
visibility only, permitting the application's existing account/session cascades
after the owning ancestor is gone in the deleting transaction. Composite foreign
keys remove corresponding generation state and pointers on those cascades. The
rebuild operation itself never initiates deletion. Ordinary verification reads
are MVCC reads, not child row locks.

Each batch transaction includes inserted chunks, revision changes, cursors and
any selection update. A pre-commit failure or observed cancellation rolls them
back together. An uncertain COMMIT result requires rereading durable progress;
it is not proof of rollback. Source drift rejects the old candidate without
rebasing it; a new explicit generation is required. Repeated starts retain data
and consume additional storage. There is no automatic repair, cleanup or loop.
Privileged schema/trigger changes or arbitrary cursor/pointer edits are outside
the application guarantee.

## Explicit maintenance mode

On an already-migrated, explicitly selected database, set:

- `JOURVOLT_REBUILD_NATIVE_SHADOW=1`
- `DATABASE_URL`
- `JOURVOLT_SHADOW_REBUILD_USER_ID`
- `JOURVOLT_SHADOW_REBUILD_VEHICLE_ID`
- `JOURVOLT_SHADOW_REBUILD_PUBLIC_ID`

Run the API binary once. Retain its `generation_id`, then set
`JOURVOLT_SHADOW_REBUILD_GENERATION_ID` to that value for each later batch.
Inspect `phase`, progress and `selected_at_revisions`; a finished lower-ordinal
generation can correctly be verified and unselected. This mode rejects mixed
comparison/backfill flags and exits before startup migrations, provider setup or
listeners. Failures use a generic log without connection strings or sample data.

## Verification evidence and limits

The complete source checkout was restored at the exact baseline. Go 1.22.12 was
downloaded from the official distribution and checksum-verified; tests use an
isolated PostgreSQL 16.2 cluster with UTC session defaults. The initial full run
found that the recreated cluster inherited a local timezone; setting its test
timezone to UTC resolved the existing date-filter fixture failure. The initial
Web install also needed a writable temporary npm cache. Neither required a
repository behavior change.

The new real-PostgreSQL tests cover missing/prefix/stale drive and charge shadows;
18 chunks across fresh OS processes; concurrent resumes; exact UTF-8/byte caps;
empty histories; source and ownership drift in all phases; immutable guards;
rolled-back account cascades; competing publication order and finished retries.
Observed-lock tests cover parent-before-generation and a competing next-key
INSERT. Checkpoint faults and in-flight cancellation in both phases preserve
full generation fingerprints, including a selection update attempted before a
verification checkpoint failure. An injected, checksum-valid incorrect build is
rejected by independent comparison; a fresh correct sibling succeeds. Constraint
and duplicate-key failures roll back their generation revision increments.

Final local validation: **409 Go/PostgreSQL test/subtest records passed, zero
failures or skips**, plus vet, build, module verification and expanded race
(15.073 seconds). The core rebuild tests passed race ×3, and separate checkpoint,
lock, corruption and constraint tests passed race ×3. Web history regressions
passed 13/13 with a clean install and production build (local Node 24.19.0;
hosted CI uses its pinned Node version). The exact hosted result is linked from
PR #12 after publication. The new feature tests passed their
first executed implementation run; no pre-existing production defect or baseline
red regression is claimed. Negative fault/corruption cases were injected only in
the isolated synthetic database. The CI gate requires all twelve new PostgreSQL
rebuild cases and includes them in the existing race selection.

Independent source review found no blocking issue. Its separate focused
PostgreSQL run under the race detector passed 56 test/subtest records with zero
failures or skips (9.602 seconds), including the fault, lock and independent
comparison cases; source and test hashes matched before and after that run.

Fixed-size native ingest state, versioned generation readers, complete raw-event
archive, realistic system-resource/long-duration testing, broker/database restart,
device restore/ACK and production acceptance remain open. Whole-JSON ingest and
full-detail reads remain, so the original OOM is not considered resolved. No
production migration/backfill, deployment, merge, credentials or retention change
was performed. Additive schema setup still takes ordinary DDL locks.
