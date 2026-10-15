# Offline compatibility qualification

Status on 2026-10-15: **QUALIFIED_LOCAL_ONLY** for the exact saved local image
artifacts and isolated synthetic checks in `candidate-4.3.0.json` and
`local-evidence-20261015.json`. `deployment_authorized` and `production_verified`
are false. Existing services were not upgraded. A separate `codex-compat430-*`
internal-only stack used only synthetic fixtures.

`qualify.py` uses Python's standard library and only reads explicitly supplied files.
It has no database/network/import/cursor interfaces and prints a report to stdout.
Inputs are limited to 16 MiB each and 500 drives / 500 charges per copy. It never
asserts whole-history coverage. Exit codes: 0 bounded match or accepted offline
evidence, 1 comparison found changes, 2 invalid/incomplete/unqualified input.

## Compare exported copies

Run from the repository root, supplying authorized **copies**, not a database file:

```powershell
python deploy/teslamate-home-docker/qualification/qualify.py compare --before before.json --after after.json --source-instance-id SYNTHETIC-SOURCE --account-uid SYNTHETIC-ACCOUNT --car-uid SYNTHETIC-CAR --from 2026-10-01T00:00:00Z --to 2026-10-02T00:00:00Z
```

Both copies must have this envelope (example contains synthetic data only):

```json
{
  "schema_version": 1,
  "identity": {"source_instance_id": "SYNTHETIC-SOURCE", "account_uid": "SYNTHETIC-ACCOUNT", "car_uid": "SYNTHETIC-CAR"},
  "interval": {"from": "2026-10-01T00:00:00Z", "to": "2026-10-02T00:00:00Z"},
  "selection": "started_at_[from,to)",
  "coverage": {
    "drives": {"complete": true, "truncated": false, "total": 1, "next_page": null},
    "charges": {"complete": true, "truncated": false, "total": 0, "next_page": null}
  },
  "drives": [{"source_record_id": "teslamate:drive:1", "identity": {"source_instance_id": "SYNTHETIC-SOURCE", "account_uid": "SYNTHETIC-ACCOUNT", "car_uid": "SYNTHETIC-CAR"}, "started_at": "2026-10-01T01:00:00Z", "units": {"length": "km", "energy": "kWh"}, "energy": null}],
  "charges": []
}
```

Selection is exactly by start time in `[from,to)`; it does not include records that
only overlap the interval. IDs, units, unknown fields, missing fields, nulls, zero,
negative numbers and exact JSON decimal values are compared without normalization.
Each row repeats the exact scoped identity. Raw `car_id` is rejected because a
local numeric ID is not a stable car UID; the scoped export producer must normalize
it explicitly. Present top-level scope fields must also match. Start and non-null
end times must be UTC and end cannot precede start. Unsupported unit strings remain
opaque and unchanged; no conversion or semantic validation is claimed.
Date filtering requires explicit UTC. Field differences include the supplied raw
timestamp representation. Coverage assertions must come from the export producer;
the tool rejects missing/mismatched counts, duplicate IDs, unfinished pages and
declared partial/truncated copies, but cannot detect a dishonest completeness claim
or verify a remote server. Reports contain ID hashes and changed field names,
not source/account/car identifiers or old/new values. Field names themselves should
remain non-sensitive. Treat exported copies as private; do not add them to Git.

An existing ID changing is an `old_id_corrections` finding. This is a review result,
not an import instruction. `archive-bridge/archive.go` and `db.go` currently read
only IDs above their saved keyset cursor. Therefore historical corrections below
that cursor are not recovered by a successful ordinary archive poll. This tooling
does not reset the cursor or write raw history to work around that limitation.

## Validate the candidate

```powershell
python deploy/teslamate-home-docker/qualification/qualify.py manifest deploy/teslamate-home-docker/qualification/candidate-4.3.0.json
python -m unittest discover -s deploy/teslamate-home-docker/qualification -p test_qualify.py -v
```

The validator requires TeslaMate 4.3.0 and exact release versions, immutable
images and one platform for TeslaMate, TeslaMateApi, Grafana, PostgreSQL, the local
adapter and archive bridge. `latest`, missing digests, missing components and a
qualified checkbox are rejected. A candidate can become `VERIFIED_OFFLINE` only
with an evidence JSON file bound by SHA-256 that records `ISOLATED_EXACT_STACK`,
the exact six `observed_components`, `observed_at` in UTC, a nonempty `run_id`,
`source_bindings` matching current Go/module/Dockerfile source hashes and these
checks all `PASS`: `schema`, `null_units_time`, `pagination`,
`bounded_reconciliation`, `restore`. Each check also requires an actual artifact
file and matching SHA-256 in `artifacts`. `source_bindings()` documents the exact hash
algorithm: normalize CRLF to LF for supported UTF-8 `.go`, `go.mod`, `go.sum` and
Dockerfile text, then hash sorted POSIX relative path NUL bytes NUL. This includes
tests; Windows CRLF and Linux LF checkouts bind identically, while semantic changes
invalidate the hash. Changed images, local source, evidence bytes, failed checks or missing
checks invalidate the receipt. This validates an operator's local evidence bundle;
it does not authenticate the issuer or manufacture a test run from declarations.
Keep synthetic validator-test receipts separate from actual exact-stack evidence.

For locally built adapter/bridge variants, `kind: local-image` requires an exact
Docker image ID (`image: sha256:...`) plus an `artifact` with saved OCI/Docker tar
path and SHA-256. The validator checks the tar index references that ID, recursively
verifies referenced OCI blob digests, typed index/manifest/config roles, schema/media
types, descriptor sizes, config rootfs/layer counts, and platform. Config-as-root
archives fail even with correct tar and blob hashes. BuildKit attestation artifacts
are handled separately and cannot stand in for the runnable image. It never invents a registry
digest from a local image ID. Missing/wrong tar, image ID, platform and artifact
hashes fail. Such a successful bundle is **QUALIFIED_LOCAL_ONLY**, with
`deployment_authorized: false`; it is not remotely deployable. PostgreSQL's native
two-part patch version (for example `18.4`) is accepted without inventing a tag.

The candidate override must be passed explicitly as a second `-f` file. Missing
API/Postgres/adapter/bridge image variables stop interpolation. `build: !reset null`
prevents implicit local builds replacing qualified images. This file is a reviewable
template, not an authorization to run `up`, migrate a database, reuse production
volumes or substitute production credentials. A separate isolated project, private
copied data, distinct ports/volumes and tested restore procedure are prerequisites
for a later authorized exact-stack run. MQTT/broker and host/network production
behavior remain outside this receipt and require separate runtime evidence.

## Actual isolated regression evidence

The tests `compatibility_pg18_test.go` in both Go modules are opt-in. Normal Go
suites skip them unless `MATELINK_COMPAT_DATABASE_URL` is supplied. The tests only
SELECT and require a disposable marker and PostgreSQL 18. The marker is created by
`fixture-v430.sql`; it is not a security boundary against a dishonest operator.
`fixture-unsupported.sql` creates a deliberately incompatible copied schema so the
adapter must return a PostgreSQL error rather than fabricate zero/empty success.

On 2026-10-15 the real immutable TeslaMate 4.3.0 image ran
`bin/teslamate eval 'TeslaMate.Release.migrate()'` against a new PG18.4 database
(105 migrations), then only the synthetic fixture SQL was inserted. The tests
exposed two queries using point-table `charges` for session fields. The minimal
fix uses `charging_processes` in Parked and Standby; captured real PG18 RED output
shows 42703 errors and GREEN output includes linked zero-energy charging, cross-car
rejection and unsupported-schema rejection. The production containers were untouched.

The restored fixture database passed real bridge keyset/scoping/null/time/units
queries and real PollOnce -> TLS synthetic receipts -> cursor -> no duplicate poll.
The built bridge application image also imported exactly three drives/one charge
to `fixture_sink.go`, using a generated synthetic TLS certificate and a new E:
cursor directory. It never contacted the real archive endpoint. The fixture sink
only retains counts; it can also probe TeslaMate HTTP and Grafana health plus a
real provisioned PostgreSQL datasource query with `FIXTURE_MODE=probe`.

The real API 1.25.0 image and built adapter image passed local GET endpoint, auth,
cross-car and proxy checks on the migrated fixture. Read parameters are forwarded
unchanged; this does not assert that upstream honors unsupported `page`/`size`
parameters. Bridge SQL keyset pagination is tested independently.

PostgreSQL `pg_dump -Fc` -> a different empty fixture DB -> `pg_restore` passed,
with equal migration/record counts and actual bridge queries after restore. This
does not verify restoring a production backup or downgrade/rollback of real data.
The running TeslaMate app may repair its own synthetic primary fixture; the
restored fixture used for bridge evidence remains independent.

## Provenance

This Python comparison/evidence validator and the added Go regression tests were
independently implemented in MateLink on 2026-10-15. No upstream source, fixtures,
characterization harness, dashboard or asset was copied. Existing local adapter
and archive-bridge production code was read for contract tests; only the two adapter
SQL table references described above changed.

Public reference: [TeslaMate v4.3.0 release](https://github.com/teslamate-org/teslamate/releases/tag/v4.3.0)
describes its own characterization suite. That suite's coverage is upstream's claim,
not a result for MateLink. [TeslaMate's license](https://github.com/teslamate-org/teslamate/blob/v4.3.0/LICENSE)
and release licensing note identify AGPL-3.0-or-later; preserve its source/notices and
review distribution obligations before bundling upstream code or images.
[TeslaMateApi license](https://github.com/tobiasehlert/teslamateapi/blob/main/LICENSE)
is MIT and is tracked independently; release 1.25.0 was selected by actual image
version/revision labels and matching public tag digest. Runtime
image license review (including Grafana, Postgres and dependencies) is NOT_RUN.

The two observed public Docker registry 4.3.0 index digests are in the manifest.
Each header digest was checked against SHA-256 of the returned manifest bytes;
these prove registry metadata, not deployed versions or combination compatibility.
Downloaded metadata is only in
`E:\Claude_allow\Download\matelink-safety-stage-20261015\registry-evidence.json`
and adjacent manifest JSON files. TeslaMate 4.3.0 layers and the official tagged
source archive were downloaded separately for isolated migration/runtime checks;
the Grafana target is a 475,852,709-byte compressed AMD64 image. The actual isolated
Grafana 13.2.2 health endpoint reported `database: ok`; its provisioned TeslaMate
PostgreSQL datasource executed SQL and returned the expected three fixture drives.
Basic authentication was enabled only for this synthetic probe on the internal
network, and no port was published. Docker image
storage is managed by the existing daemon; no daemon settings were changed.

CI can run real schema regressions without Tesla accounts: start the exact Postgres image, execute the immutable TeslaMate image release migration command, then apply `fixture-v430.sql` and `fixture-unsupported.sql`. Both Go modules need `MATELINK_COMPAT_DATABASE_URL`; the adapter negative case additionally needs `MATELINK_COMPAT_UNSUPPORTED_DATABASE_URL` with `search_path=compat_unsupported`. Without these variables the ordinary suite explicitly skips these integration tests. Optional actual API/running-image checks use `MATELINK_COMPAT_UPSTREAM_URL` (isolated hostname `api`) and `MATELINK_COMPAT_RUNNING_ADAPTER_URL` (hostname `adapter`). The source fixture SQL alone is not a replacement for real upstream migrations.
