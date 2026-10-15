# Safety stage: upstream compatibility lane (2026-10-15)

Jovi, real isolated PG18/TeslaMate 4.3.0 schema tests found and fixed two adapter SQL errors. Existing services, credentials, volumes and history/cursor files were not changed. Only new `codex-compat430-*` containers and E: synthetic artifacts were used.

**Exact six-component combination: QUALIFIED_LOCAL_ONLY** for the saved local images and isolated synthetic evidence. `deployment_authorized=false` and `production_verified=false`. Production/provider/device and whole-history acceptance remain NOT_RUN.

## Current image observations

Read-only image metadata of the six existing containers showed TeslaMate `v4.0.1` (revision `46a755b7ae45f818fce11db4df6cdccc16d8f9b5`) and TeslaMateApi `1.25.0` (revision `7c3d3a628f50009174b0f2913ccf34c58c4edcd6`). Grafana, Postgres and the two local images lacked version labels. Postgres's exact image executed `postgres --version` in a separate no-network disposable container and reported 18.4. These are image/binary observations, not a production data-path or provider test. The current public `postgres:18.4-trixie` tag resolves a different image; the lock keeps the exact locally tested digest instead of substituting it.

## Actual regression and repair

The immutable TeslaMate 4.3.0 image applied its actual release migrations to a fresh Postgres 18.4 database: 105 migrations. Only the checked-in synthetic fixture was then inserted. Actual adapter Snapshot and cross-car checks passed, but Parked and Standby failed with SQLSTATE 42703 because they queried point-table `charges` for session fields.

The two table references now use `charging_processes`. Actual Go-to-PG18 tests passed for Snapshot, linked zero-energy charging, Standby, cross-car rejection and an unsupported schema returning an explicit 42703 error. The fixed adapter application image also passed actual HTTP endpoint/auth/isolation checks.

TeslaMateApi 1.25.0 ran in the new internal-only network with its own MQTT broker. Actual `/cars`, date-filtered `/drives` and `/charges` responses passed through the real Go proxy unchanged. Forwarding pagination parameters is not proof upstream honors them; actual bridge SQL keyset pagination was tested separately through PostgreSQL.

The actual bridge source passed null/zero/negative/time/unit/scoped paging checks, then PollOnce -> HTTPS synthetic receipt -> cursor -> no duplicate second poll. The built bridge application image separately sent three synthetic drives and one charge to its own TLS fixture sink, recording exactly four requests and cursor 102/501. No real archive endpoint was used. The sink stores counts, not submitted records. The existing bridge still does not discover old-ID corrections below its cursor.

A custom-format dump was restored into another empty disposable DB. The 105-migration/2-car/4-drive/7-position/2-session/2-point counts matched, and actual bridge queries/polling passed on the restored fixture. This is a synthetic restore test, not verification of a production backup or downgrade. The running TeslaMate app may repair only its own synthetic primary fixture; the restored fixture is independent.

## Bounded comparison and immutable local images

The CLI requires matching source instance, account UID, stable car UID and UTC interval on both envelopes and every row. Raw local `car_id`, foreign row scopes, bad timestamps/ranges, duplicate IDs, partial/truncated/count-mismatched pages and more than 500 records per kind fail. Unknown fields, unsupported unit strings, missing/null/zero/negative and exact decimal distinctions are retained. It compares copies, reports old-ID corrections, and never writes history/cursors. Actual synthetic exported-copy comparison passed unchanged input and detected an injected older-ID correction without modifying its DB.

All six selections are recorded in the candidate lock. Local adapter/bridge use exact Docker image IDs with saved E: tar artifacts and SHA-256; no registry digest was invented or published. The validator verifies tar index/image ID, referenced OCI blob hashes and platform. It also requires current local source hashes, the exact component combination, a SHA-256-bound receipt and actual hashed artifacts for every check. A local-only success never authorizes deployment or claims remote availability.

Verification: Python CLI suite **20 PASS**; both ordinary Go suites **PASS**; actual PG18/real API/running adapter/bridge integration and synthetic restore **PASS**; Compose parsing with synthetic env **PASS**. Grafana's fixed AMD64 image downloaded successfully; actual Grafana 13.2.2 health reported `database: ok` and its provisioned TeslaMate PostgreSQL datasource returned exactly three fixture drives through a real SQL query. TeslaMate 4.3.0 HTTP also returned 200. All six fixed images ran in the internal-only stack with no published ports. Source binding normalizes CRLF to LF, preserving the same Go-source identity on Windows and Linux. The local receipt hashes every required evidence artifact; another machine missing the saved local tars must reject it.

Evidence is in `E:\Claude_allow\Download\matelink-safety-stage-20261015`: current image/public registry metadata, retained RED/GREEN integration logs, dump/restore receipt, bounded comparison receipt, saved local image tars and synthetic TLS/cursor files. Usage, fixture schemas, licensing/source attribution and receipt requirements are in [`qualification/README.md`](../deploy/teslamate-home-docker/qualification/README.md). No upstream harness/test fixtures were copied into MateLink; official release source/images remain separate downloaded provenance artifacts.

Local qualification receipt: [`local-evidence-20261015.json`](../deploy/teslamate-home-docker/qualification/local-evidence-20261015.json). Candidate lock: [`candidate-4.3.0.json`](../deploy/teslamate-home-docker/qualification/candidate-4.3.0.json). These bind exact image identities and Go source bytes; they do not publish the local images, approve a production upgrade, or verify real accounts/history.

After evidence capture, the eight owned isolated containers were stopped by exact name after verifying their purpose label. Their E: fixture data, local images, archives and internal replay network were retained. Existing containers were not stopped or restarted. See `isolated-resource-closeout.json` in the evidence directory.

Independent review found and closed a local-archive validation bypass: a config blob can no longer substitute for an OCI image root. Root/index/manifest/config roles, schema/media types, descriptor sizes, blob hashes and rootfs/layer counts are now checked. Actual BuildKit attestations use a separate typed artifact path and cannot substitute for the runnable image. The config-as-root negative fixture and both real saved archives were revalidated.
