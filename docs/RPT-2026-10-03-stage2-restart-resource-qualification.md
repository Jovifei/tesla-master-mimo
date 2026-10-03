# Stage 2 process/cancellation/resource qualification — 2026-10-03

Base: `ad78840f0ebef0f69fbdbd4519e261bdff673223`, existing draft PR #12 branch.
Production source is unchanged in this slice. All work used isolated synthetic
PostgreSQL 16.2 and Go 1.22.12 on Linux; no production DB, device, broker,
credential, deployment, raw deletion or retention policy was touched.

## Durability gates now demonstrated

1. Start an actual helper OS process, ingest a synthetic charging start and energy
   observations, and wait for a durable-commit marker. Kill that process without
   graceful cleanup. Start a new OS process with a new PostgreSQL connection,
   replay the original observations, continue charging, and complete. Verified:
   no replay rewrite, one completed session, retained energy baseline (10 → 16
   gives 6), two retained samples, synchronized summary, and no duplicate
   completion. This is direct-ingest event replay across process death, **not**
   actual MQTT transport/QoS negotiation, broker restart or PostgreSQL restart.
2. Introduce a synthetic-row-only UPDATE trigger that exposes an advisory lock
   and blocks the backfill. Observe that lock before canceling the live context.
   Wait for backend transaction release, compare full-row fingerprints including
   raw JSON and summary columns, then retry. Verified: in-flight cancellation
   returns, all rows unchanged, lock released, and three rows materialized on retry.

Full final Go/PostgreSQL suite: **314 PASS, 0 FAIL, 0 SKIP** including subtests.
`go vet`, build, module verification, targeted race including both new durability
regressions, formatting and diff checks: PASS. The previous source slice's Web
13/13 and clean production build remain unchanged; exact-head CI for this new
qualification slice follows publication.

## Bounded synthetic resource matrix

Eight scenarios: 5,000 and 29,583 original points per session, each at concurrent
workers 2/4/8/16, eight rounds per worker. Each scenario uses a fresh Go test
process; one independent account/vehicle/session per worker. The fixture coordinates
are synthetic/repeated and compressible. Metadata is warmed; shared database cache
is not reset between cases. All runs used GOMAXPROCS=9 and pgx pool maximum=9.

Each round calls the detail HTTP handler (including serialization to a counting
**discard writer**, no network), page HTTP handler (requested page20 returns one
row), and direct metadata service method (no HTTP serialization). The process
includes the driver, sampler, runtime and handlers. CPU is measured over the
workload after forced GC. RSS/heap peaks are sampled every 2 ms and may miss shorter
spikes. Quantiles use nearest rounded index `(n-1)*p`; these small, single-pass
samples are **not production p95/p99 SLO or user-capacity estimates**.

| Points/session | Concurrency | Detail 200 / 429 | Sampled peak RSS MiB | Cumulative allocation MiB | Detail 200 p95 ms | Page p95 ms | Metadata p95 ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5,000 | 2 | 16 / 0 | 32.49 | 139.44 | 27.40 | 1.48 | 0.49 |
| 5,000 | 4 | 32 / 0 | 46.92 | 273.20 | 34.68 | 2.40 | 1.24 |
| 5,000 | 8 | 64 / 0 | 90.89 | 533.96 | 56.63 | 9.43 | 1.83 |
| 5,000 | 16 | 64 / 64 | 74.84 | 526.55 | 57.95 | 10.77 | 2.57 |
| 29,583 | 2 | 16 / 0 | 114.83 | 945.32 | 150.90 | 2.16 | 0.43 |
| 29,583 | 4 | 32 / 0 | 185.97 | 1866.04 | 187.55 | 8.22 | 0.91 |
| 29,583 | 8 | 64 / 0 | 394.65 | 3643.28 | 254.30 | 14.75 | 4.32 |
| 29,583 | 16 | 64 / 64 | 416.50 | 3613.19 | 221.30 | 20.80 | 6.91 |

At concurrency16, the current global admission cap8 rejects excess detail work;
all page/metadata calls succeeded and all permits were released. The original
point count was unchanged after every case. At the larger point size, sampled
RSS still reached **394.65–416.50 MiB** for this handler-driver process. This is
clear evidence that full-detail work remains materially expensive; it does not
close the original OOM risk or justify a production budget without host/service
headroom and realistic-data measurements. No production admission limit changed.

The JSON receipt includes full heap/RSS/allocation/CPU values, p95/p99, individual
HTTP status counts, sample counts, source hashes and receipt hashes:
[`resource-matrix.json`](evidence/2026-10-03-history-qualification/resource-matrix.json).

PostgreSQL backend statistics are explicitly flushed on **every idle pool
connection** before both database snapshots. Review found that merely waiting
1.1 seconds was insufficient for PostgreSQL 16's asynchronous reporting; the
initial numbers were discarded and all scenarios rerun. Database read/hit block
counters include control-query overhead and shared database activity; they are
**logical shared-buffer statistics, not physical disk I/O**. Temporary-byte delta
was zero in these fixtures. Optional same-host postmaster/child CPU ticks were
available locally (100 ticks/second); in container-separated CI they remain null.
Initial DDL/index-lock risk and one legacy JSON value's PostgreSQL allocation
remain unqualified by this resource harness.

## Reproduction and next gates

Commands and opt-in guards are documented in the service README. CI requires the
actual-process and in-flight-cancellation tests and executes all eight synthetic
scenarios, preserving raw logs in its evidence artifact. The read-only independent
review found the stats-flush and error-path connection-release issues; both were
repaired before final receipts. Independent final review found no remaining blocker
and matched all eight checked-in measurements/raw-receipt hashes, both test-source
hashes, and the 314/0/0 full-suite records. This is source/evidence signoff, not
production or deployment acceptance.

Still open: bounded point/chunk persistence, checksummed manifests and partial-import
recovery; actual broker/network/DB restart; HTTP network/auth/login isolation under
load; realistic/compressed-data and long-duration system RSS/CPU/physical-I/O
profiling; production/device qualification and an explicitly authorized deployment.
The persisted-summary and synthetic qualification gates do not complete Stage 2.
