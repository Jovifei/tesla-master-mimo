# Stage 2 persisted PostgreSQL summaries — 2026-10-03

Baseline: `a68e54cc1ac1ebce2874096909775cb1c70af5dd` on the existing
`codex/history-resource-stage2-20261002` branch (draft PR #12, stacked on PR #11).
This implements the independent-summary part of ADR
`ADR-2026-10-02-tiered-history-storage.md` section 6. It does not complete Stage 2.

## Connected implementation

- Additive PostgreSQL endpoint/count columns, format version, and write revision.
  An actual database trigger populates them atomically on all route/charge JSON
  inserts or updates. Native telemetry, legacy local imports, archive imports,
  and older binaries writing the same table share this behavior.
- Native capitalized JSON coordinates and archive lower-case coordinates are
  handled; persisted nulls and observed zeros remain distinct. Counts describe
  the stored original arrays, including archive points with null coordinates.
- Paginated history queries carry no raw JSON for materialized rows. Both page and
  summary queries use `CASE` to select stored coordinates, so a null coordinate
  does not cause a fallback payload read. Unprocessed rows keep legacy fallback;
  malformed old payloads retain their existing explicit read-error behavior.
- An explicit maintenance mode processes at most 100 unlocked pending rows in one
  atomic, five-second-cancelable batch. It performs no provider/listener startup,
  unrelated schema migrations, indefinite loop, or raw JSON replacement. The
  README documents invocation and pending/materialized/invalid counts. A zero
  batch count is not a completion proof when another worker holds row locks.
- Invalid legacy JSON is marked version `-1`, preserved, and excluded from repeat
  backfill attempts. Later corrected payload writes regenerate its summary.
  Format version and write revision are not archive manifest hashes; identical
  import writes can advance the revision. Deduplicated QoS1 events do not.

## Verification of final source

Environment: isolated synthetic PostgreSQL 16.2 over loopback TCP and Go 1.22.12.
No production database, credentials, original mobile data, or provider was used.

- Full Go + PostgreSQL suite: **311 PASS, 0 FAIL, 0 SKIP**, including subtests.
- Five new PostgreSQL tests: import replay/dedup and transactional rollback;
  archive null/zero/complete sample counts; telemetry service restart and QoS1
  replay without a summary rewrite; persisted null reads that demonstrably avoid
  corrupt raw payloads; bounded/cancelable/skip-locked/idempotent backfill with
  identical before/after raw-content hashes and malformed-row recovery.
- `go vet ./...`, module verification, build: PASS.
- Targeted Linux race suite including all new summary tests: PASS.
- Explicit maintenance executable against the isolated migrated database: PASS,
  exits after one batch without starting HTTP/MQTT.
- Web clean install, **13/13** history pagination regressions, production build:
  PASS. Existing large-bundle warning remains.
- Formatting and `git diff --check`: PASS.
- Independent read-only final-diff review: no blocking issue; source hashes and
  PostgreSQL receipts independently checked.
- Existing synthetic large-history fixture: 413 drives / 632,055 raw points;
  metadata+exists cumulative allocation 2,979 bytes per operation pair, page20
  150,664 bytes, legacy full-history 546,036,264 bytes. These are Go cumulative
  allocations for that fixture/run, not RSS, per-server memory, or PG I/O evidence.

CI now requires the five new PostgreSQL tests and includes them in the race gate.
Local receipts: `go-tests.json`, `counts.json`, `go-vet.log`, `go-build.log`,
`go-mod-verify.log`, `go-race.log`, `backfill-command.log`, `web-tests.log`, and
`web-build.log`. Exact remote commit/CI verification follows publication.

## Remaining gates

The additive migration still takes table DDL locks and builds a pending-row index;
zero-downtime migration has not been demonstrated. Backfill bounds row count and
duration, not one large legacy JSON value's database memory use. The cancellation
regression cancels before execution; in-flight cancellation rollback is not yet
qualified. The restart regression reconstructs the service on the same database
pool rather than restarting the operating-system process or database. Bounded point/chunk writes, checksummed manifests, full end-to-end
restart/partial-import qualification, and concurrency 2/4/8/16 RSS/PG CPU-I/O/p95
measurements remain open. The narrow service restart/QoS1 regression above is not
full broker/process/device qualification. No main merge, deployment, production
backfill, deletion, TTL/retention change, or Stage 2 phone acceptance is claimed.
