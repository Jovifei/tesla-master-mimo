# Stage 2 import/archive local verification — 2026-10-02

Remote request source: e75a452405deb02c3e0a72d05e1157614d91773e. Base source blobs and unique patches verified before materialization. Shared admission now covers importHistory materialization and persistence; archive uses this service entry without nested acquisition. Local residual fixes map ordinary import overload to 429/Retry-After and cancellation to 408, and check cancellation during session conversion and before memory writes.

Seven genuine service/HTTP regressions cover overload/no write, release, pre-cancel/no write/no leak, account identity separation, and ordinary/archive HTTP status behavior. Endpoint tests use the memory adapter; PostgreSQL behavior is verified separately by the full suite.

Final local Go + isolated PostgreSQL16: 294 PASS, zero fail/skip. go vet, go build, go mod verify: PASS. Linux targeted history/admission race and separate ArchiveImport race: PASS, both exit 0. Evidence: stage2-import-go-final.json, stage2-import-linux-race.log, stage2-import-archive-linux-race.log under E:/Claude_allow/Download/matelink-oom-local-20261002. gofmt and git diff --check: PASS.

Partial scope: JSON decode occurs before shared service admission, so decode allocation is not yet protected. Per-point/chunk persistence, independent persisted summaries, restart/QoS1 and 2/4/8/16 evidence remain unfinished. The e34f527 summary/chunk request defines an unconnected unbounded in-memory helper and is CHANGES_REQUIRED; it is not materialized. This report does not claim Stage2 completion, production deployment, or phone validation.
